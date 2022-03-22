package cachesnn

import spinal.core._
import spinal.lib._
import cachesnn.Synapse._
import cachesnn.Cache._
import spinal.core.internals.PhaseMemBlackboxing
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc.{BusSlaveFactoryRead, SizeMapping}

object Cache {
  val dataByte = 4
  val memDataByte = dataByte*channels
  val innerCacheAxiSlow = Axi4Config(
    addressWidth = log2Up(cacheSize),
    dataWidth = 32,
    idWidth = 1,
    useRegion = false,
    useCache = false,
    useProt = false,
    useQos = false
  )
  val innerCacheAxiFast = innerCacheAxiSlow.copy(
    dataWidth = 32*channels
  )
}
/*
case class CacheOut() extends Bundle {
  val preNeuronId = UInt(neuronIdWidth bits)
  val stdpParam = ???
  val ltpOnly = Bool()
}
*/
class Cache(nParallel: Int) extends Component {
  val io = new Bundle {
    //val dataBus = ???
    //val spike = slave Stream new AerBus
    //val ltpEvent = slave Stream UInt(neuronIdWidth bits)
    //val cacheOut = master Stream CacheOut()
  }
  stub()
}

case class MemWritePort[T <: Data](dataType : T, addressWidth:Int) extends Bundle {
  val address = UInt(addressWidth bits)
  val data = cloneOf(dataType)
  val mask = Bits(dataType.getBitsWidth/8 bits)
}

case class MemWritePortStream[T <: Data](dataType : T, addressWidth:Int) extends Bundle with IMasterSlave {
  val cmd = Stream(MemWritePort(dataType, addressWidth))

  override def asMaster():Unit = {
    master(cmd)
  }

  def toAxi4WriteOnly(c:Axi4Config):Axi4WriteOnly={
    val ret = Axi4WriteOnly(c)
    val cmdJoin = StreamJoin(ret.writeCmd.unburstify, ret.writeData)
    cmd << cmdJoin.translateWith{
      val w = MemWritePort(dataType, addressWidth)
      w.address := cmdJoin._1.addr(c.wordRange)
      w.mask := cmdJoin._2.strb
      w.data.assignFromBits(cmdJoin._2.data)
      w
    }
    ret.writeRsp.valid := cmdJoin.fire && cmdJoin._1.last
    ret.writeRsp.id := cmdJoin._1.id
    ret
  }
}

case class MemReadPortStream[T <: Data](dataType: T, addressWidth: Int) extends Bundle with IMasterSlave {
  val cmd = Stream(UInt(addressWidth bit))
  val rsp = Stream(dataType)

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }

  def toAxi4ReadOnlySlave(c:Axi4Config, readLatency:Int):Axi4ReadOnly = {
    val ret = Axi4ReadOnly(c)
    val (axiCmdF0, axiCmdF1) = StreamFork2(ret.readCmd.unburstify)
    val axiCmdDelay = axiCmdF0.queue(readLatency+1)

    cmd.arbitrationFrom(axiCmdF1)
    cmd.payload := axiCmdF1.addr(c.wordRange)

    val axiRsp = StreamJoin(axiCmdDelay, rsp)
    ret.readRsp.arbitrationFrom(axiRsp)
    ret.readRsp.data := axiRsp._2.asBits
    ret.readRsp.last := axiRsp._1.last
    ret.readRsp.id := axiRsp._1.id
    ret
  }
}

class XilinxUram(wordWidth: Int, wordCount: BigInt) extends BlackBox {
  assert(wordWidth % 8 == 0)
  addGenerics(("AWIDTH", log2Up(wordCount)))
  addGenerics(("DWIDTH", wordWidth))
  addGenerics(("NUM_COL", wordWidth / 8))

  def addressWidth = log2Up(wordCount)

  def dataType = Bits(wordWidth bits)

  val io = new Bundle {
    val clk = in Bool()
    val reset = in Bool()
    val r = slave(MemReadPortStream(dataType, addressWidth))
    val w = slave(Flow(MemWritePort(dataType, addressWidth)))
  }
  noIoPrefix()
  mapCurrentClockDomain(io.clk, io.reset)

  private def renameIO(): Unit = {
    io.flattenForeach(bt=>{
      bt.setName(bt.getName().replace("_payload", ""))
    })
    io.r.rsp.payload.setPartialName("data")
    io.r.cmd.payload.setPartialName("address")
  }

  addPrePopTask(() => renameIO())
  addRTLPath(s"src/main/verilog/xilinx/XilinxUram.v")
}

case class UramBank[T <: Data](dataType:T, wordCount: BigInt){
  def addressWidth = log2Up(wordCount)

  val uram = new XilinxUram(dataType.getBitsWidth, wordCount)

  def writePort = uram.io.w

  def streamReadSync[T2 <: Data](cmd: Stream[UInt], linkedData: T2, nPipe: Int = 3): Stream[ReadRetLinked[T, T2]] = {
    uram.io.r.cmd << cmd
    val ret = Stream(ReadRetLinked(dataType, linkedData))
    val retLinked = RegNextWhen(linkedData, cmd.ready)
    ret.arbitrationFrom(uram.io.r.rsp)
    ret.value := uram.io.r.rsp.payload.asInstanceOf[T]
    ret.linked := retLinked

    Seq.fill(nPipe)(null)
      .foldLeft(ret){(rsp, _) => rsp.stage() }
  }
}

class CacheBanks(wordWidth: Int, lineSize: BigInt = 2 KiB, nBanks: Int = 8) extends Component {
  def dataType = Bits(wordWidth bits)
  def UramSize: BigInt = 32 KiB
  def CacheSize: BigInt = UramSize * nBanks
  def addressWidth = log2Up(CacheSize * 8 / wordWidth)
  def lineWordCount = lineSize * 8 / wordWidth
  def bankWordCount = UramSize * 8 / wordWidth
  def bankAddressWidth = log2Up(bankWordCount)
  def UramLatency = 4 // Uram has 1 fixed latency and nPipe = 3 latency
  def toBankAddress(address:UInt):UInt ={
    // bank address mapping (using word address)
    // aer     : |-   line id h  -|- line id l -|-    offset    -|
    // address : |- inner bank h -|- bank sel  -|- inner bank l -|

    val lowWidth = log2Up(lineWordCount)
    val heightWidth = address.getWidth - lowWidth - log2Up(nBanks)
    (address.takeHigh(heightWidth) ## address.takeLow(lowWidth)).asUInt
  }
  def getAxi4Config:Axi4Config = {
    Axi4Config(
      addressWidth = log2Up(CacheSize),
      dataWidth = wordWidth,
      idWidth = 2,
      useRegion = false,
      useCache = false,
      useProt = false,
      useQos = false,
      useResp = false,
      useLock = false
    )
  }

  val nRead, nWrite = 2

  val io = new Bundle {
    val read = Vec(slave(MemReadPortStream(dataType, addressWidth)), nRead)
    val write = Vec(slave(MemWritePortStream(dataType, addressWidth)), nWrite)
  }

  val banks = Seq.fill(nBanks)(
    UramBank(dataType, bankWordCount)
  )

  case class readWithId() extends Bundle {
    val address = UInt(bankAddressWidth bits)
    val readPortId = UInt(log2Up(nRead) bits)
  }

  val allRead = io.read.zipWithIndex.flatMap{case (r, id) =>
    val bankSel = r.cmd.payload(offset = log2Up(lineWordCount), log2Up(nBanks) bits)
    val cmd = r.cmd.translateWith{
      val ret = readWithId()
      ret.address := toBankAddress(r.cmd.payload)
      ret.readPortId := id
      ret
    }
    StreamDemux(cmd, bankSel, nBanks)
  }

  val allWrite = io.write.flatMap{w =>
    val bankSel = w.cmd.address(offset = log2Up(lineWordCount), log2Up(nBanks) bits)
    val writeCmd = w.cmd.translateWith{
      val ret = MemWritePort(dataType, bankAddressWidth)
      ret.assignSomeByName(w.cmd.payload)
      ret.address.removeAssignments()
      ret.address := toBankAddress(w.cmd.address)
      ret
    }
    StreamDemux(writeCmd, bankSel, nBanks)
  }

  // connect write cmd
  for((b,id) <- banks.zipWithIndex){
    val thisWrites = (id until allWrite.length by nBanks).map(allWrite(_))
    b.writePort << StreamArbiterFactory.on(thisWrites).toFlow
  }

  val allRsp = banks.zipWithIndex.flatMap{case(b, id) =>
    val thisReads  = (id until allRead.length by nBanks).map(allRead(_))
    val thisRead = StreamArbiterFactory.on(thisReads)
    val readCmd = thisRead.translateWith(thisRead.address)
    val readRspLinked = b.streamReadSync(readCmd, thisRead.readPortId)
    val readRsp = readRspLinked.translateWith(readRspLinked.value)
    StreamDemux(readRsp, readRspLinked.linked, nRead)
  }

  // connect read rsp
  for((r, id) <- io.read.zipWithIndex){
    val thisRsps = (id until allRsp.length by nRead).map(allRsp(_))
    r.rsp << StreamArbiterFactory.on(thisRsps)
  }
}
/*
class CacheBanks extends Component{

  val io = new Bundle {
    val slowBus = slave(Axi4(innerCacheAxiSlow))
    val fastRead = slave(Axi4ReadOnly(innerCacheAxiFast))
    val fastWrite = slave(Axi4WriteOnly(innerCacheAxiFast))
  }

  val banks = Seq.fill(nBank)(new Area{
    val ram = Mem(Bits(memDataByte*8 bits), bankWordCount)
      .generateAsBlackBox()
    val axiConfig = innerCacheAxiFast.copy(
      addressWidth = log2Up(cacheSize/nBank),
      idWidth = 2
    )
    val axi = Axi4(axiConfig)

    val aw = axi.aw.unburstify
    val ar = axi.ar.unburstify

    val wStage0 = aw.haltWhen(aw.valid && !axi.writeData.valid)
    ram.write(
      address = wStage0.addr(axiConfig.wordRange),
      data = axi.writeData.data,
      enable = wStage0.fire,
      mask = axi.writeData.strb
    )
    axi.writeData.ready := wStage0.ready

    val wStage1 = wStage0.stage()
    wStage1.ready := axi.writeRsp.ready || !wStage1.last

    axi.writeRsp.valid := wStage1.valid && wStage1.last
    axi.writeRsp.id := wStage1.id
    axi.writeRsp.setOKAY()

    val rStage = ar.stage()
    rStage.ready := axi.readRsp.ready

    axi.readRsp.data := ram.readSync(
      address = ar.addr(axiConfig.wordRange),
      enable = ar.fire
    )
    axi.readRsp.valid  := rStage.valid
    axi.readRsp.id  := rStage.id
    axi.readRsp.last := rStage.last
    axi.readRsp.setOKAY()
  })


  val fastBus = {
    val upSizer = Axi4Upsizer(innerCacheAxiSlow, innerCacheAxiFast, 4)
    upSizer.io.input << io.slowBus
    upSizer.io.output
  }

  val cc = Axi4CrossbarFactory()
  val addressSizePerBank = bankWordCount*memDataByte
  val addressMapping = (0 until nBank).map{id=>
    println(s"${(id*addressSizePerBank).hexString()} ${addressSizePerBank.hexString()}")
    SizeMapping(id*addressSizePerBank, addressSizePerBank)
  }
  val bankAxis = banks.map(_.axi)
  for((axi, address) <- bankAxis.zip(addressMapping)){
    cc.addSlaves((axi, address))
  }
  cc.addConnections(
    (fastBus, bankAxis),
    (io.fastWrite, bankAxis),
    (io.fastRead, bankAxis)
  )
  cc.build()
}*/

object CacheVerilog extends App {
  SpinalVerilog(new CacheBanks(64, 2048))
}