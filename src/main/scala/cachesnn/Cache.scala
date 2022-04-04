package cachesnn

import spinal.core._
import spinal.lib._
import cachesnn.Cache._
import cachesnn.Synapse._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.bmb.Bmb
import spinal.lib.bus.regif.BusInterface
import spinal.lib.bus.simple._
import spinal.lib.fsm._

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
  val ssnWidth = nidWidth + 1  // spike sequence number, the worst cast has max spike and max virtual spike
  val oooLengthMax = 128
  val cacheReadDelay = 3
}

object TagState extends SpinalEnum {
  val HIT, LOCKED, AVAILABLE, REPLACE, FAIL = newElement()
  // bigger UInt encoding has higher priority
  defaultEncoding = SpinalEnumEncoding("staticEncoding")(
    HIT       -> 4,
    LOCKED    -> 3,
    AVAILABLE -> 2,
    REPLACE   -> 1,
    FAIL      -> 0
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
  val thread = UInt(log2Up(threads) bits)
  val timeStamp = UInt(tagTimeStampWidth bits)
}

case class TagRamWriteCmd() extends Bundle {
  val address = UInt(tagRamAddressWidth bits)
  val tags = Vec(Tag(), wayCountPerStep)
  val wayMask = Bits(wayCountPerStep bits)

  def setAddress(way:UInt, setIndex:UInt): Unit ={
    address := setIndex @@ way.dropLow(log2Up(wayCountPerStep)).asUInt
  }
  def setMask(way:UInt): Unit ={
    wayMask := UIntToOh(way.takeLow(log2Up(wayCountPerStep)).asUInt, wayCountPerStep)
  }
}

case class TagRamBus() extends Bundle with IMasterSlave {
  val readCmd = Stream(UInt(tagRamAddressWidth bits))
  val readRsp = Stream(Vec(Tag(), wayCountPerStep))
  val writeCmd = Stream(TagRamWriteCmd())

  override def asMaster() = {
    master(readCmd, writeCmd)
    slave(readRsp)
  }
}

class SpikeTagFilter extends Component {
  import TagState._

  val io = new Bundle {
    val metaSpike = slave(Stream(MetaSpikeT()))
    val refractory = in UInt(tagTimeStampWidth bits)
    val tagRamBus = master(TagRamBus())
    val missSpike = master(Stream(new MissSpike))
    val readySpike = master(Stream(new ReadySpike))
  }

  case class WaySelFolder() extends Bundle{
    val way = UInt(log2Up(wayCount) bits)
    val state = TagState()
    def priority = state.asBits.asUInt
    def isRollBackState:Bool = state===LOCKED || state===FAIL
    def isMissState:Bool = state===AVAILABLE || state===REPLACE
  }
  val spikeRollBackFifo = StreamFifo(MetaSpikeT(), oooLengthMax)

  val (spike, step) = StreamArbiterFactory.roundRobin
    .on(Seq(io.metaSpike, spikeRollBackFifo.io.pop))
    .repeat(tagStep)

  val stepDelay = Delay(step, 1, spike.ready)
  val spikeDelay = Delay(spike.payload, 1, spike.ready)

  val read = new Area {
    io.tagRamBus.readCmd.arbitrationFrom(spike)
    io.tagRamBus.readCmd.payload := io.metaSpike.nid(setIndexRange) @@ step
  }

  val tags = io.tagRamBus.readRsp.payload

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
  val noneLock = Cat(noneLockOh).orR
  val replaceWayAuxiliary = OHToUInt(OHMasking.first(noneLockOh))

  val currentWaySel = WaySelFolder()
  val waySelFolder = Reg(WaySelFolder())
  when(hit){
    currentWaySel.way := stepDelay @@ hitWay
    currentWaySel.state := HIT
  }elsewhen locked {
    currentWaySel.way := stepDelay @@ lockedWay
    currentWaySel.state := LOCKED
  }elsewhen available {
    currentWaySel.way := stepDelay @@ availableWay
    currentWaySel.state := AVAILABLE
  }elsewhen replace {
    currentWaySel.way := stepDelay @@ replaceWay
    currentWaySel.state := REPLACE
  }elsewhen noneLock {
    currentWaySel.way := stepDelay @@ replaceWayAuxiliary
    currentWaySel.state := REPLACE
  }otherwise{
    currentWaySel.way := 0
    currentWaySel.state := FAIL
  }

  when(stepDelay===U(0) || currentWaySel.priority > waySelFolder.priority){
    waySelFolder := currentWaySel
  }

  val rsp = io.tagRamBus.readRsp.translateWith{
    val ret = new MetaSpike
    ret.assignSomeByName(spikeDelay)
    ret
  }.takeWhen(stepDelay===U(tagStep-1))

  val (toTagUpdateSpike, toReadySpike, toMissSpike, toRollBackSpike) = {
    val ret = StreamFork(rsp, 4)
    (
      ret(0).throwWhen(waySelFolder.isRollBackState),
      ret(1).throwWhen(waySelFolder.isMissState),
      ret(2).takeWhen(waySelFolder.isMissState),
      ret(3).takeWhen(waySelFolder.isRollBackState)
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

  io.tagRamBus.writeCmd << toTagUpdateSpike.translateWith{
    val newTag = Tag()
    newTag.lock := True
    newTag.valid := True
    newTag.timeStamp := spikeDelay.tagTimeStamp
    newTag.nid := spikeDelay.nid
    newTag.thread := spikeDelay.thread
    val ret = cloneOf(io.tagRamBus.writeCmd)
    ret.setAddress(waySelFolder.way, toTagUpdateSpike.nid(setIndexRange))
    ret.setMask(waySelFolder.way)
    ret.tags := Vec(Seq.fill(wayCountPerStep)(newTag))
    ret
  }

  spikeRollBackFifo.io.push << toRollBackSpike.translateWith{
    val ret = MetaSpikeT()
    ret.assignSomeByName(toRollBackSpike.payload)
    ret.tagTimeStamp := spikeDelay.tagTimeStamp
    ret
  }
}

trait SpikeCacheData extends Bundle {
  val data = Bits(busDataWidth bits)
}

case class MissSpikeWithData() extends MissSpike with SpikeCacheData
case class MetaSpikeWithData() extends MetaSpike with SpikeCacheData

class MissSpikeCtrl extends Component {
  val io = new Bundle {
    val writeBackSpikeData = master(Stream(Fragment(MetaSpikeWithData())))
    val missSpikeData = slave(Stream(Fragment(MissSpikeWithData())))
    val readySpike = master(Stream(ReadySpike()))
    val cache = master(Axi4(cacheAxi4Config))
  }

  val spikeFork = new Area{
    private val missSpikeFork = StreamFork2(io.missSpikeData)
    private val writeDataSrc  = StreamDemux(missSpikeFork._1, (missSpikeFork._1.tagState===TagState.REPLACE).asUInt, 2)
    private val busHeaderSrc  = StreamFork2(missSpikeFork._2.takeWhen(missSpikeFork._2.isFirst))

    private val readHeaderSrc  = StreamFork2(busHeaderSrc._1.takeWhen(busHeaderSrc._1.tagState===TagState.REPLACE))
    val writeHeader = busHeaderSrc._2

    val readHeader = readHeaderSrc._1
    val readHeaderDelay = readHeaderSrc._2.queue(2)

    val availableWrite = writeDataSrc(0).stage()
    val replaceWrite = writeDataSrc(1).queue(cacheReadDelay+1)
  }

  val axi = new Area {
    import spikeFork._

    io.cache.readCmd.arbitrationFrom(readHeader)
    io.cache.readCmd.addr := readHeader.cacheAddressBase
    io.cache.readCmd.size := Axi4.size.BYTE_8.asUInt
    io.cache.readCmd.burst := Axi4.burst.INCR
    io.cache.readCmd.len := readHeader.len.resized

    io.cache.writeCmd.arbitrationFrom(writeHeader)
    io.cache.writeCmd.addr := writeHeader.cacheAddressBase
    io.cache.writeCmd.size := Axi4.size.BYTE_8.asUInt
    io.cache.writeCmd.burst := Axi4.burst.INCR
    io.cache.writeCmd.len := writeHeader.len.resized

    val readRspCounter, writeDataCounter= Counter(cacheLenMax)
    when(io.cache.readRsp.fire) {
      when(io.cache.readRsp.last) {
        readRspCounter.clear()
      }otherwise{
        readRspCounter.increment()
      }
    }
    when(replaceWrite.fire) {
      when(io.cache.writeData.last) {
        writeDataCounter.clear()
      }otherwise{
        writeDataCounter.increment()
      }
    }

    val replaceWriteConsistency = replaceWrite.haltWhen(
      readRspCounter===writeDataCounter
    )

    val writeData = StreamArbiterFactory.on(
      Seq(replaceWriteConsistency, availableWrite)
    )
    io.cache.writeData.arbitrationFrom(writeData)
    io.cache.writeData.data := writeData.data
    io.cache.writeData.last := writeData.last
    io.cache.writeData.strb := writeData.last ? writeData.lastByteMask | B(0xFF)
  }

  io.writeBackSpikeData << io.cache.readRsp.translateWith{
    val ret = cloneOf(io.writeBackSpikeData.payload)
    ret.last := io.cache.readRsp.last
    ret.data := io.cache.readRsp.data
    ret.fragment.assignUnassignedByName(spikeFork.readHeaderDelay.fragment)
    ret
  }

  val writeOver = new Area{
    import spikeFork.readHeaderDelay

    val event = StreamJoin(io.cache.writeRsp, readHeaderDelay)
    io.readySpike.arbitrationFrom(event)
    io.readySpike.payload.assignAllByName(readHeaderDelay.fragment)
  }
}

class SpikeOrderCtrl extends Component {
  val io = new Bundle {
    val sequentialInSpike = slave(Stream(MetaSpikeT()))
    val metaSpikeWithSsn = master(Stream(MetaSpikeT()))
    val ssnClear = slave(Stream(UInt(log2Up(threads) bits)))
    val oooAckSpike = slave(Stream(AckSpike()))
    val tagRamBus = master(TagRamBus())
  }

  class RobItem extends Bundle {
    val ack = Bool()
    val spike = AckSpike()
  }
  object RobItem {
    def apply() = new RobItem
    def apply(ack:Bool, spike:AckSpike) = {
      val ret = new RobItem
      ret.ack := ack
      ret.spike := spike
      ret
    }
  }

  val threadLogic = (0 until threads).map { thread =>
    new Area {
      val rob = Mem(RobItem(), oooLengthMax)
      val inSsn, ackSsn = Counter(ssnWidth bits)
      val threadDown = inSsn===ackSsn
      val robFull = inSsn-ackSsn < oooLengthMax

      val clear = cloneOf(io.ssnClear)
      val oooAck, sequentialAck = Stream(AckSpike())
      val spikeIn = Stream(MetaSpikeT())
      val spikeOut = spikeIn.haltWhen(robFull).transmuteWith{
        val ret = MetaSpikeT()
        ret.ssn := inSsn
        ret.assignUnassignedByName(spikeIn.payload)
        ret
      }
      clear.ready := False
      oooAck.ready := False
      val readPort = rob.readSyncPort
      val writePort = rob.writePort
      readPort.cmd := readPort.cmd.getZero
      writePort := writePort.getZero
      sequentialAck.valid := False
      sequentialAck.payload := readPort.rsp.spike

      val fsm = new StateMachine{
        val SsnAppend = new State with EntryPoint
        val SsnAckSet = new State
        val SsnAck = new State
        val SsnClear = new State

        SsnAppend
          .whenIsActive{
            when(clear.valid && threadDown){ goto(SsnClear) }
            when(oooAck.valid){ goto(SsnAckSet) }
            when(spikeIn.fire){
              inSsn.increment()
              writePort.valid := True
              writePort.address := inSsn.resized
              writePort.data := RobItem(False, AckSpike().getZero)
            }
          }

        SsnClear
          .whenIsActive{ goto(SsnAppend) }
          .onEntry{
            inSsn.clear()
            ackSsn.clear()
          }
          .onExit{
            clear.ready := True
          }

        SsnAckSet
          .whenIsActive{ goto(SsnAck) }
          .onEntry{
            writePort.valid := True
            writePort.address := oooAck.ssn.resized
            writePort.data := RobItem(True, oooAck.payload)
          }

        SsnAck
          .whenIsActive{
            when(!readPort.rsp.ack || threadDown){
              goto(SsnAppend)
            }
            sequentialAck.valid := True
          }
          .whenIsNext{
            when(sequentialAck.ready){
              ackSsn.increment()
            }
            readPort.cmd.valid := True
            readPort.cmd.payload := ackSsn.resized
          }
          .onExit{
            oooAck.ready := True
          }
      }
    }
  }

  val spikeInDe = StreamDemux(io.sequentialInSpike, io.sequentialInSpike.thread, threads)
  val spikeOooAckDe = StreamDemux(io.oooAckSpike, io.oooAckSpike.thread, threads)
  val clearDe = StreamDemux(io.ssnClear, io.ssnClear.payload, threads)
  for(thread <- 0 until threads){
    spikeInDe(thread)  >> threadLogic(thread).spikeIn
    spikeOooAckDe(thread) >> threadLogic(thread).oooAck
    clearDe(thread)    >> threadLogic(thread).clear
  }
  io.metaSpikeWithSsn << StreamArbiterFactory.on(threadLogic.map(_.spikeOut))
  val sequentialAck = StreamArbiterFactory.on(threadLogic.map(_.sequentialAck))
  io.tagRamBus.readCmd.arbitrationFrom(sequentialAck)
  io.tagRamBus.readCmd.payload := sequentialAck.tagAddress
  val ackSpikeDelay = RegNextWhen(sequentialAck.payload, sequentialAck.ready)
  io.tagRamBus.writeCmd.arbitrationFrom(io.tagRamBus.readRsp)
  io.tagRamBus.writeCmd.address := ackSpikeDelay.tagAddress
  io.tagRamBus.writeCmd.wayMask := ackSpikeDelay.tagWayMask
  io.tagRamBus.writeCmd.tags := Vec(
    io.tagRamBus.readRsp.payload.map{ tag =>
      val ret = Tag()
      ret.lock := False
      ret.assignUnassignedByName(tag)
      ret
    }
  )
}

class CacheFlushCtrl extends Component {
  val io = new Bundle {
    val flushThread = slave(Stream(UInt(log2Up(threads) bits)))
    val flushAck = out Bool()
    val ssnClear = master(Stream(UInt(log2Up(threads) bits)))
    val tagRamBus = master(TagRamBus())
    val cache = master(Axi4ReadOnly(cacheAxi4Config))
    val writeBackSpikeData = master(Stream(Fragment(MetaSpikeWithData())))
  }

  val flushCounter = Counter(tagRamAddressWidth bits, io.tagRamBus.readCmd.fire)
  val flushingTag = RegInit(False)
    .riseWhen(io.ssnClear.fire)
    .fallWhen(flushCounter.willOverflow)

  io.flushThread.haltWhen(flushingTag) >> io.ssnClear
  io.tagRamBus.readCmd.valid := flushingTag
  io.tagRamBus.readCmd.payload := flushCounter
  io.flushAck := !flushingTag && io.tagRamBus.readRsp.fire

  val thread = RegNextWhen(io.flushThread.payload, io.flushThread.ready)
  val flushWrite = io.tagRamBus.readRsp.translateWith{
    val ret = cloneOf(io.tagRamBus.writeCmd.payload)
    ret.address := RegNext(flushCounter.asBits.asUInt)
    ret.tags := ret.tags.getZero
    ret.wayMask := B(io.tagRamBus.readRsp.payload.map(_.thread===thread))
    ret
  }
  io.tagRamBus.writeCmd << flushWrite.takeWhen(flushWrite.wayMask.orR)
  stub()
}

class CacheDataCtrl extends Component {
  val io = new Bundle {
    val ackSpike = slave(Stream(AckSpike()))
    val metaSpike = slave(Stream(MetaSpikeT()))
    val missSpike = master(Stream(new MissSpike))
    val refractory = in UInt(tagTimeStampWidth bits)
    val flushThread = slave(Stream(UInt(log2Up(threads) bits)))
    val flushAck = out Bool()
    val writeBackSpikeData = master(Stream(Fragment(MetaSpikeWithData())))
    val missSpikeData = slave(Stream(Fragment(MissSpikeWithData())))
    val readySpike = master(Stream(ReadySpike()))
    val cache = master(Axi4(cacheAxi4Config))
    val cacheFlush = master(Axi4ReadOnly(cacheAxi4Config))
  }

  val spikeTagFilter = new SpikeTagFilter
  val missSpikeCtrl = new MissSpikeCtrl
  val spikeOrderCtrl = new SpikeOrderCtrl
  val cacheFlushCtrl = new CacheFlushCtrl

  io.metaSpike >> spikeOrderCtrl.io.sequentialInSpike
  io.ackSpike  >> spikeOrderCtrl.io.oooAckSpike
  cacheFlushCtrl.io.ssnClear >> spikeOrderCtrl.io.ssnClear

  spikeTagFilter.io.metaSpike  << spikeOrderCtrl.io.metaSpikeWithSsn
  spikeTagFilter.io.refractory := io.refractory
  spikeTagFilter.io.missSpike  >> io.missSpike

  missSpikeCtrl.io.missSpikeData << io.missSpikeData
  missSpikeCtrl.io.cache         <> io.cache

  io.flushThread >> cacheFlushCtrl.io.flushThread
  io.flushAck    := cacheFlushCtrl.io.flushAck
  io.cacheFlush  <> cacheFlushCtrl.io.cache

  io.readySpike << StreamArbiterFactory.roundRobin.on(
    Seq(spikeTagFilter.io.readySpike, missSpikeCtrl.io.readySpike)
  ).queue(cacheLines)

  io.writeBackSpikeData << StreamArbiterFactory.fragmentLock.on(
    Seq(cacheFlushCtrl.io.writeBackSpikeData, missSpikeCtrl.io.writeBackSpikeData)
  )

  val tagRam = Seq.fill(wayCountPerStep)(
    Mem(Tag(), cacheLines/wayCountPerStep)
  )

  val tagRamBusPrioritySeq = Seq(
    cacheFlushCtrl.io.tagRamBus,
    spikeOrderCtrl.io.tagRamBus,
    spikeTagFilter.io.tagRamBus
  )

  val readCmd = StreamArbiterFactory.lowerFirst.on(tagRamBusPrioritySeq.map(_.readCmd))
  val streamReadRsp = tagRam.head.streamReadSync(readCmd)
  val rsp = streamReadRsp.translateWith{
    Vec(Seq(streamReadRsp.payload) ++ tagRam.tail.map(_.readSync(readCmd.payload, readCmd.valid)))
  }

  val readSel = RegNextWhen(OHToUInt(tagRamBusPrioritySeq.map(_.readCmd.fire)), rsp.ready)
  StreamDemux(rsp, readSel, tagRamBusPrioritySeq.length)
    .zip(tagRamBusPrioritySeq)
    .foreach{ case(a, b) => a>>b.readRsp }

  val writeCmd = StreamArbiterFactory.lowerFirst.on(
    tagRamBusPrioritySeq.map(_.writeCmd)
  ).toFlow
  for((tagRam, way) <- tagRam.zipWithIndex){
    tagRam.write(writeCmd.address, writeCmd.tags(way), writeCmd.wayMask(way))
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
    io.cache.readCmd.size := Axi4.size.BYTE_8.asUInt
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