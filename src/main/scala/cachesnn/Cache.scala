package cachesnn

import spinal.core._
import spinal.lib._
import cachesnn.Cache._
import cachesnn.Synapse._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.bmb.Bmb

object Cache {
  val tagTimeStampWidth = 4
  val cacheLineSize = 1 KiB
  val wayCount = 32
  val wayCountPerStep = 8
  val tagStep = wayCount/wayCountPerStep
  val cacheLines = (nCacheBank*cacheBankSize/cacheLineSize).toInt
  val setIndexRange = log2Up(cacheLines/wayCount)-1 downto 0
  val setSize = wayCount * 2 Byte
}

object TagState extends SpinalEnum {
  val HIT, AVAILABLE, BYPASS, REPLACE = newElement()
  // bigger UInt encoding has higher priority
  defaultEncoding = SpinalEnumEncoding("staticEncoding")(
    HIT       -> 3,
    AVAILABLE -> 2,
    REPLACE   -> 1,
    BYPASS    -> 0
  )
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

case class MemReadPortFlow[T <: Data](dataType: T, addressWidth: Int) extends Bundle with IMasterSlave {
  val cmd = Flow(UInt(addressWidth bit))
  val rsp = Flow(dataType)

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
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
    val r = slave(MemReadPortFlow(dataType, addressWidth))
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

  def streamReadSync[T2 <: Data](cmd: Stream[UInt], linkedData: T2, nPipe: Int = 2): Stream[ReadRetLinked[T, T2]] = {
    uram.io.r.cmd << cmd.toFlowFire
    val ret = Flow(ReadRetLinked(dataType, linkedData))
    val retLinked = RegNextWhen(linkedData, cmd.ready)
    ret.valid := uram.io.r.rsp.valid
    ret.value := uram.io.r.rsp.payload.asInstanceOf[T]
    ret.linked := retLinked

    val piped = (0 until nPipe).foldLeft(ret)((rsp,_)=>rsp.stage()).toStream.queueLowLatency(nPipe+1) // 1 ram delay
    cmd.ready := piped.ready
    piped
  }
}

case class Axi4UramBank(dataWidth:Int, byteCount:BigInt, idWidth:Int) extends Component {
  val axi4Config = Axi4SharedOnChipRam.getAxiConfig(dataWidth, byteCount, idWidth)

  val io = new Bundle {
    val axi = slave(Axi4(axi4Config))
  }
  val wordCount = byteCount / axi4Config.bytePerWord
  val ram = UramBank(axi4Config.dataType, wordCount)

  val write = new Area{
    val aw = io.axi.aw.unburstify
    val wStage = StreamJoin(aw, io.axi.writeData)

    val writCmd = wStage.translateWith{
      val ret = MemWritePort(axi4Config.dataType, axi4Config.wordRange.size)
      ret.address := wStage._1.addr(axi4Config.wordRange)
      ret.data := wStage._2.data
      ret.mask := wStage._2.strb
      ret
    }

    ram.writePort << writCmd.asFlow
    io.axi.writeRsp.arbitrationFrom(writCmd)
    io.axi.writeRsp.setOKAY()
    io.axi.writeRsp.id := wStage._1.id
  }

  val read = new Area{
    val ar = io.axi.ar.unburstify
    val cmd:Stream[UInt] = ar.translateWith(ar.addr(axi4Config.wordRange))
    val rsp = ram.streamReadSync(cmd, ar.payload)
    io.axi.readRsp.arbitrationFrom(rsp)
    io.axi.readRsp.id := rsp.linked.id
    io.axi.readRsp.last := rsp.linked.last
    io.axi.readRsp.setOKAY()
    io.axi.readRsp.data := rsp.value
  }
}

case class Tag() extends Bundle {
  val valid = Bool()
  val nid = UInt(nidWidth bits)
  val timeStamp = UInt(tagTimeStampWidth bits)
}

class SpikeTagFilter extends Component {
  val io = new Bundle {
    val metaSpike = slave(Stream(MetaSpikeT()))
    val refractory = in UInt(tagTimeStampWidth bits)
    val tagRam = master(Bmb(tagRamBmbParameter))
    val missSpike = master(Stream(new MissSpike))
    val readySpike = master(Stream(new ReadySpike))
  }

  case class WaySelFolder() extends Bundle{
    val way = UInt(log2Up(wayCount) bits)
    val state = TagState()
    def priority = state.asBits.asUInt
  }

  val read = new Area {
    io.tagRam.cmd.arbitrationFrom(io.metaSpike)
    io.tagRam.cmd.address := (io.metaSpike.nid(setIndexRange) << log2Up(setSize)).resized
    io.tagRam.cmd.opcode := Bmb.Cmd.Opcode.READ
    io.tagRam.cmd.length := tagStep-1
    io.tagRam.cmd.last := True
    io.tagRam.cmd.fragment.assignUnassignedByName(io.tagRam.cmd.fragment.getZero)
  }

  val spike = RegNextWhen(io.metaSpike.payload, io.metaSpike.ready)
  val stepCnt = Counter(tagStep, io.tagRam.rsp.fire)
  val tags = io.tagRam.rsp.data
    .subdivideIn(16 bits)
    .map{b=>
      val ret = Tag()
      ret.assignFromBits(b)
      ret
    }

  val hitsOh = tags.map(tag => tag.valid && tag.nid===spike.nid)
  val hit = Cat(hitsOh).orR
  val hitWay = OHToUInt(hitsOh)

  val availableOh = tags.map(!_.valid)
  val available = Cat(availableOh).orR
  val availableWay = OHToUInt(availableOh)

  val replacesOh = tags.map(tag => tag.valid && (spike.tagTimeStamp-tag.timeStamp)>io.refractory)
  val replace = Cat(replacesOh).orR
  val replaceWay = OHToUInt(replacesOh)

  val currentWaySel = WaySelFolder()
  val waySelFolder = Reg(WaySelFolder())
  when(hit){
    currentWaySel.way := stepCnt @@ hitWay
    currentWaySel.state := TagState.HIT
  }elsewhen available {
    currentWaySel.way := stepCnt @@ availableWay
    currentWaySel.state := TagState.AVAILABLE
  }elsewhen replace {
    currentWaySel.way := stepCnt @@ replaceWay
    currentWaySel.state := TagState.REPLACE
  }otherwise {
    currentWaySel.way := 0
    currentWaySel.state := TagState.BYPASS
  }

  when(io.tagRam.rsp.isFirst || currentWaySel.priority > waySelFolder.priority){
    waySelFolder := currentWaySel
  }

  val spikeDelay = io.tagRam.rsp.translateWith{
    val ret = new MetaSpike
    ret.assignSomeByName(spike)
    ret
  }.takeWhen(io.tagRam.rsp.last)

  val (missSpike, hitSpike) = StreamFork2(spikeDelay)
  val cacheAddressBase = (spikeDelay.nid(setIndexRange) @@ waySelFolder.way) << log2Up(cacheLineSize)
  io.missSpike << missSpike
    .takeWhen(waySelFolder.state=/=TagState.HIT)
    .translateWith{
      val ret = new MissSpike
      ret.assignSomeByName(spikeDelay.payload)
      ret.cacheAddressBase := cacheAddressBase
      ret.writeBack := waySelFolder.state===TagState.REPLACE
      ret
    }
  io.readySpike << hitSpike
    .takeWhen(waySelFolder.state===TagState.HIT)
    .translateWith{
      val ret = ReadySpike()
      ret.assignSomeByName(spikeDelay.payload)
      ret.cacheAddressBase := cacheAddressBase
      ret
    }
}

class CacheDataPacker extends Component {
  val io = new Bundle {
    val input = slave(Stream(ReadySpike()))
    val cache = master(Axi4ReadOnly(cacheAxi4Config))
    val output = master(Stream(Fragment(new SynapseData)))
  }

  // dispatch pet-spike to cache read port by address low to avoid read conflict
  val spikeDispatch = new Area {
    val sel = cacheAddressToBankSel(io.input.cacheAddressBase)
    val scatterSpikes = StreamDemux(io.input, sel, nCacheBank).map(_.stage())
    val noConflictSpike = StreamArbiterFactory.roundRobin.on(scatterSpikes)
  }

  val (spikeToAxi, spikeToBuffer) = StreamFork2(spikeDispatch.noConflictSpike)
  val spikeBuffer = spikeToBuffer.queue(2)

  val readCmdSend = new Area {
    io.cache.readCmd.arbitrationFrom(spikeToAxi)
    io.cache.readCmd.size := Axi4.size.BYTE_64.asUInt
    io.cache.readCmd.burst := Axi4.burst.INCR
    io.cache.readCmd.addr := spikeToAxi.cacheAddressBase
    io.cache.readCmd.len := spikeToAxi.len.resized
  }

  val readRspRec = new Area {
    val readRsp = io.cache.readRsp
    val axiLastReady = readRsp.ready && readRsp.last

    val recWordCount = Counter(cacheLenWidth bits, readRsp.fire && spikeBuffer.dense)
    when(axiLastReady) { recWordCount.clear() }

    spikeBuffer.ready := axiLastReady

    io.output << readRsp.translateWith{
      val ret = new SynapseData
      ret.assignSomeByName(spikeBuffer.payload)
      ret.data := readRsp.data
      ret.offset := recWordCount
      when(spikeBuffer.dense){
        ret.weightValid := readRsp.last ? spikeBuffer.lastWordMask | B"1111"
        ret.postNidBase := spikeBuffer.threadAddressBase + (recWordCount @@ U"00")
      }otherwise{
        // weightValid 4 b | postNid 12 b | 16 b * 3 data |
        ret.weightValid := readRsp.data.takeHigh(4)
        ret.postNidBase := spikeBuffer.threadAddressBase + readRsp.data(16*3, nidWidth bits).asUInt
      }
      ret
    }.addFragmentLast(readRsp.last)
  }
}

object CacheVerilog extends App {
  //SpinalVerilog(Axi4UramBank(64, 32 KiB, 2))
  SpinalVerilog(new SpikeTagFilter)
}