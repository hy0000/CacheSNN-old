package cachesnn

import spinal.core._
import spinal.lib._
import cachesnn.Cache._
import cachesnn.Synapse._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.bmb.Bmb
import spinal.lib.bus.regif.BusInterface
import spinal.lib.bus.simple._

object Cache {
  val tagTimeStampWidth = 3
  val cacheLineSize = 1 KiB
  val cacheLines = (nCacheBank*cacheBankSize/cacheLineSize).toInt
  val wayCount = 32
  val wayCountPerStep = 8
  val setIndexRange = log2Up(cacheLines/wayCount)-1 downto 0
  val setSize = wayCount * 2 Byte
  val tagStep = wayCount/wayCountPerStep
  val bypassBufferItems = 8
  val tagRamAddressWidth = setIndexRange.size + log2Up(tagStep)
  val tagRamDataWidth = wayCountPerStep*16
  val oooLengthMax = 128
}

object TagState extends SpinalEnum {
  val HIT, LOCKED, AVAILABLE, REPLACE = newElement()
  // bigger UInt encoding has higher priority
  defaultEncoding = SpinalEnumEncoding("staticEncoding")(
    HIT       -> 3,
    LOCKED    -> 2,
    AVAILABLE -> 1,
    REPLACE   -> 0
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
  val lock = Bool()
  val nid = UInt(nidWidth bits)
  val timeStamp = UInt(tagTimeStampWidth bits)
}

class SpikeTagFilter extends Component {
  val io = new Bundle {
    val metaSpike = slave(Stream(MetaSpikeT()))
    val refractory = in UInt(tagTimeStampWidth bits)
    val tagRead = master(MemReadPortStream(Bits(tagRamDataWidth bits), tagRamAddressWidth))
    val tagWrite = master(Stream(MemWritePort(Bits(tagRamDataWidth bits), tagRamAddressWidth)))
    val missSpike = master(Stream(new MissSpike))
    val readySpike = master(Stream(new ReadySpike))
  }

  case class WaySelFolder() extends Bundle{
    val way = UInt(log2Up(wayCount) bits)
    val state = TagState()
    def priority = state.asBits.asUInt
  }
  val lockedSpikeRollBackFifo = StreamFifo(MetaSpikeT(), cacheLines)

  val (spike, step) = StreamArbiterFactory.roundRobin
    .on(Seq(io.metaSpike, lockedSpikeRollBackFifo.io.pop))
    .repeat(tagStep)

  val stepDelay = Delay(step, 1, spike.ready)
  val spikeDelay = Delay(spike.payload, 1, spike.ready)

  val read = new Area {
    io.tagRead.cmd.arbitrationFrom(spike)
    io.tagRead.cmd.payload := io.metaSpike.nid(setIndexRange) @@ step
  }

  val tags = Vec(
    io.tagRead.rsp.payload
      .subdivideIn(16 bits)
      .map{b =>
        val ret = Tag()
        ret.assignFromBits(b)
        ret
      }
  )

  val hitsOh = tags.map(tag => tag.valid && tag.nid===spikeDelay.nid)
  val hit = Cat(hitsOh).orR
  val hitWay = OHToUInt(OHMasking.first(hitsOh))

  val lockedOh = tags.map(tag => tag.valid && tag.nid===spikeDelay.nid && tag.lock)
  val locked = Cat(lockedOh).orR
  val lockedWay = OHToUInt(OHMasking.first(lockedOh))

  val availableOh = tags.map(!_.valid)
  val available = Cat(availableOh).orR
  val availableWay = OHToUInt(OHMasking.first(availableOh))

  val replacesOh = tags.map(tag => tag.valid && (spikeDelay.tagTimeStamp-tag.timeStamp)>io.refractory)
  val replace = Cat(replacesOh).orR
  val replaceWay = OHToUInt(OHMasking.first(replacesOh))

  val noneLockOh = tags.map(tag => tag.valid && !tag.lock)
  val replaceWayAuxiliary = OHToUInt(OHMasking.first(noneLockOh))

  val currentWaySel = WaySelFolder()
  val waySelFolder = Reg(WaySelFolder())
  when(hit){
    currentWaySel.way := stepDelay @@ hitWay
    currentWaySel.state := TagState.HIT
  }elsewhen locked {
    currentWaySel.way := stepDelay @@ lockedWay
    currentWaySel.state := TagState.LOCKED
  }elsewhen available {
    currentWaySel.way := stepDelay @@ availableWay
    currentWaySel.state := TagState.AVAILABLE
  }elsewhen replace {
    currentWaySel.way := stepDelay @@ replaceWay
    currentWaySel.state := TagState.REPLACE
  }otherwise {
    currentWaySel.way := stepDelay @@ replaceWayAuxiliary
    currentWaySel.state := TagState.REPLACE
  }

  when(stepDelay===U(0) || currentWaySel.priority > waySelFolder.priority){
    waySelFolder := currentWaySel
  }

  val rsp = io.tagRead.rsp.translateWith{
    val ret = new MetaSpike
    ret.assignSomeByName(spikeDelay)
    ret
  }.takeWhen(stepDelay===U(tagStep-1))

  val (toTagUpdateSpike, toReadySpike, toMissSpike, toLockedSpike) = {
    val ret = StreamFork(rsp, 4)
    (
      ret(0).throwWhen(waySelFolder.state===TagState.HIT),
      ret(1).takeWhen(waySelFolder.state===TagState.HIT),
      ret(2).takeWhen(waySelFolder.state===TagState.HIT || waySelFolder.state===TagState.AVAILABLE),
      ret(3).takeWhen(waySelFolder.state===TagState.LOCKED)
    )
  }

  val cacheAddressBase = (spikeDelay.nid(setIndexRange) @@ waySelFolder.way) << log2Up(cacheLineSize)

  io.missSpike << toMissSpike.translateWith{
    val ret = new MissSpike
    ret.assignSomeByName(spikeDelay)
    ret.cacheAddressBase := cacheAddressBase
    ret.tagState := waySelFolder.state
    ret
  }

  io.readySpike << toReadySpike.translateWith{
    val ret = ReadySpike()
    ret.assignSomeByName(spikeDelay)
    ret.cacheAddressBase := cacheAddressBase
    ret
  }

  io.tagWrite << toTagUpdateSpike.translateWith{
    val newTag = Tag()
    newTag.lock := True
    newTag.valid := True
    newTag.timeStamp := spikeDelay.tagTimeStamp
    newTag.nid := spikeDelay.nid
    val ret = cloneOf(io.tagWrite)
    val maskWidth = log2Up(wayCountPerStep)
    ret.address := toTagUpdateSpike.nid(setIndexRange) @@ waySelFolder.way.dropLow(maskWidth).asUInt
    ret.data := Seq.fill(wayCountPerStep)(newTag.asBits).reduce(_##_)
    ret.mask := 0
    ret.mask(waySelFolder.way.takeLow(maskWidth).asUInt, 2 bits) := B"11"
    ret
  }

  lockedSpikeRollBackFifo.io.push << toLockedSpike.translateWith{
    val ret = MetaSpikeT()
    ret.assignSomeByName(toLockedSpike.payload)
    ret.tagTimeStamp := spikeDelay.tagTimeStamp
    ret
  }
}

case class MissSpikeWithData() extends Bundle {
  val spike = new MissSpike
  val data = Bits(64 bits)
}

class MissSpikeCtrl extends Component {
  val io = new Bundle {
    val packer = master(Stream(Fragment(MissSpikeWithData())))
    val unpacker = slave(Stream(Fragment(MissSpikeWithData())))
    val tagRead = master(MemReadPortStream(Bits(tagRamDataWidth bits), tagRamAddressWidth))
    val tagWrite = master(Stream(MemWritePort(Bits(tagRamDataWidth bits), tagRamAddressWidth)))
    val readySpike = master(Stream(ReadySpike()))
    val cache = master(Axi4(cacheAxi4Config))
  }
  val writeBackBuffer = Mem(Bits(busDataWidth bits), cacheLenMax)
  stub()
}

class SpikeOrderCtrl extends Component {
  val io = new Bundle {
    val sequentialInSpike = slave(Stream(MetaSpikeT()))
    val metaSpikeWithSsn = master(Stream(MetaSpikeT()))
    val oooAckSpike = slave(Stream(AckSpike()))
    val tagRead = master(MemReadPortStream(Bits(tagRamDataWidth bits), tagRamAddressWidth))
    val tagWrite = master(Stream(MemWritePort(Bits(tagRamDataWidth bits), tagRamAddressWidth)))
    val threadDone = out Vec(Bool(), threads)
  }
  stub()
}

class TagFlushCtrl extends Component {
  val io = new Bundle {
    val flushThread = slave(Stream(UInt(log2Up(threads) bits)))
    val flushAck = master(Event)
    val tagRead = master(MemReadPortStream(Bits(tagRamDataWidth bits), tagRamAddressWidth))
    val tagWrite = master(Stream(MemWritePort(Bits(tagRamDataWidth bits), tagRamAddressWidth)))
    val threadDone = in Vec(Bool(), threads)
  }
  stub()
}

class CacheDataCtrl extends Component {
  val io = new Bundle {
    val ackSpike = slave(Stream(AckSpike()))
    val metaSpike = slave(Stream(MetaSpikeT()))
    val missSpike = master(Stream(new MissSpike))
    val refractory = in UInt(tagTimeStampWidth bits)
    val flushThread = slave(Stream(UInt(log2Up(threads) bits)))
    val flushAck = master(Event)
    val packer = master(Stream(Fragment(MissSpikeWithData())))
    val unpacker = slave(Stream(Fragment(MissSpikeWithData())))
    val readySpike = master(Stream(ReadySpike()))
    val cache = master(Axi4(cacheAxi4Config))
    val threadDone = out Vec(Bool(), threads)
  }

  val spikeTagFilter = new SpikeTagFilter
  val missSpikeCtrl = new MissSpikeCtrl
  val spikeOrderCtrl = new SpikeOrderCtrl
  val tagFlushCtrl = new TagFlushCtrl
  val tagRam = Mem(Bits(tagRamDataWidth bits), cacheLines/wayCountPerStep)

  io.metaSpike  >> spikeOrderCtrl.io.sequentialInSpike
  io.ackSpike   >> spikeOrderCtrl.io.oooAckSpike
  io.threadDone := spikeOrderCtrl.io.threadDone

  spikeTagFilter.io.metaSpike  << spikeOrderCtrl.io.metaSpikeWithSsn
  spikeTagFilter.io.refractory := io.refractory
  spikeTagFilter.io.missSpike  >> io.missSpike

  missSpikeCtrl.io.packer   >> io.packer
  missSpikeCtrl.io.unpacker << io.unpacker
  missSpikeCtrl.io.cache    <> io.cache

  tagFlushCtrl.io.flushThread << io.flushThread
  tagFlushCtrl.io.flushAck    >> io.flushAck
  tagFlushCtrl.io.threadDone  := spikeOrderCtrl.io.threadDone

  io.readySpike << StreamArbiterFactory.roundRobin.on(
    Seq(spikeTagFilter.io.readySpike, missSpikeCtrl.io.readySpike)
  ).queue(cacheLines)


  val readPrioritySeq = Seq(
    tagFlushCtrl.io.tagRead,
    spikeOrderCtrl.io.tagRead,
    missSpikeCtrl.io.tagRead,
    spikeTagFilter.io.tagRead
  )

  val writePrioritySeq = Seq(
    tagFlushCtrl.io.tagWrite,
    spikeOrderCtrl.io.tagWrite,
    missSpikeCtrl.io.tagWrite,
    spikeTagFilter.io.tagWrite
  )

  val readCmd = StreamArbiterFactory.lowerFirst.on(readPrioritySeq.map(_.cmd))
  val rsp = tagRam.streamReadSync(readCmd)
  val readSel = RegNextWhen(OHToUInt(readPrioritySeq.map(_.cmd.fire)), rsp.ready)
  StreamDemux(rsp, readSel, readPrioritySeq.length)
    .zip(readPrioritySeq)
    .foreach{ case(a, b) => a>>b.rsp }

  val writeCmd = StreamArbiterFactory.lowerFirst.on(writePrioritySeq).toFlow
  tagRam.write(writeCmd.address, writeCmd.data, writeCmd.valid, writeCmd.mask)
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
  SpinalVerilog(new CacheDataCtrl)
}