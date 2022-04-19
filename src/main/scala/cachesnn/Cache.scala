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
  val cacheLines = (nCacheBank * cacheBankSize/cacheLineSize).toInt
  val cacheLineAddrWidth = log2Up(cacheLineSize)
  val wayCount = 16
  val wayCountPerStep = 4
  val setIndexRange = log2Up(cacheLines/wayCount)-1 downto 0
  val setSize = wayCount * 2 Byte
  val tagWidth = nidWidth - setIndexRange.size
  val tagStep = wayCount/wayCountPerStep
  val bypassBufferItems = 8
  val tagRamAddressWidth = setIndexRange.size + log2Up(tagStep)
  val ssnWidth = nidWidth + 1  // spike sequence number, the worst cast has max spike and max virtual spike
  val oooLengthMax = 128
  val cacheReadDelay = 3
}

object TagState extends SpinalEnum {
  val HIT = newElement("hit")
  val LOCKED = newElement("locked")
  val AVAILABLE = newElement("available")
  val REPLACE = newElement("replace")
  val FAIL = newElement("fail")
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
  var axi4Config = Axi4SharedOnChipRam.getAxiConfig(dataWidth, byteCount, idWidth)

  val io = new Bundle {
    val axi = slave(Axi4(axi4Config))
  }
  val wordCount = byteCount / axi4Config.bytePerWord
  val ram = UramBank(axi4Config.dataType, wordCount)

  val write = new Area{
    val aw = io.axi.aw.s2mPipe().unburstify
    val wStage = StreamJoin(aw, io.axi.writeData)

    val writCmd = wStage.translateWith{
      val ret = MemWritePort(axi4Config.dataType, axi4Config.wordRange.size)
      ret.address := wStage._1.addr(axi4Config.wordRange)
      ret.data := wStage._2.data
      ret.mask := wStage._2.strb
      ret
    }

    ram.writePort << writCmd.asFlow
    io.axi.writeRsp.arbitrationFrom(writCmd.takeWhen(io.axi.writeData.last))
    io.axi.writeRsp.setOKAY()
    io.axi.writeRsp.id := wStage._1.id
  }

  val read = new Area{
    val ar = io.axi.ar.s2mPipe().unburstify
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
  val dirty = Bool()
  val tag = UInt(tagWidth bits)
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
    val idle = out Bool()
    val rollBackSpike = master Stream MetaSpikeT()
  }

  case class WaySelFolder() extends Bundle{
    val way = UInt(log2Up(wayCount) bits)
    val state = TagState()
    val tag = Tag()
    def priority = state.asBits.asUInt
    def isRollBackState:Bool = state===LOCKED || state===FAIL
    def isMissState:Bool = state===AVAILABLE || state===REPLACE
  }

  val metaSpike = io.metaSpike
  val spikeBuffer = Reg(metaSpike.payload)
  val stepCmd = Counter(tagStep, io.tagRamBus.readCmd.fire)
  val stepRsp= Counter(tagStep, io.tagRamBus.readRsp.fire)
  val rspEvent = Event

  io.tagRamBus.readCmd.valid := False
  io.tagRamBus.readCmd.payload := spikeBuffer.nid(setIndexRange) @@ stepCmd
  io.tagRamBus.readRsp.ready := True
  metaSpike.ready := False
  rspEvent.valid := False

  val tagDetect = new Area{
    val tags = io.tagRamBus.readRsp.payload

    val hitsOh = tags.map(tag => tag.valid && tag.tag===spikeBuffer.tag && !tag.lock)
    val hit = Cat(hitsOh).orR
    val hitWay = OHToUInt(OHMasking.first(hitsOh))

    val lockedOh = tags.map(tag => tag.valid && tag.tag===spikeBuffer.tag && tag.lock)
    val locked = Cat(lockedOh).orR
    val lockedWay = OHToUInt(OHMasking.first(lockedOh))

    val availableOh = tags.map(!_.valid)
    val available = Cat(availableOh).orR
    val availableWay = OHToUInt(OHMasking.first(availableOh))

    val replacesOh = tags.map(tag => tag.valid && (spikeBuffer.tagTimeStamp-tag.timeStamp)>io.refractory)
    val replace = Cat(replacesOh).orR
    val replaceWay = OHToUInt(OHMasking.first(replacesOh))

    val noneLockOh = tags.map(tag => tag.valid && !tag.lock)
    val noneLock = Cat(noneLockOh).orR
    val auxiliaryReplaceWay = OHToUInt(OHMasking.first(noneLockOh))

    val currentWaySel = WaySelFolder()
    when(hit){
      currentWaySel.way := stepRsp @@ hitWay
      currentWaySel.state := HIT
    }elsewhen locked {
      currentWaySel.way := stepRsp @@ lockedWay
      currentWaySel.state := LOCKED
    }elsewhen available {
      currentWaySel.way := stepRsp @@ availableWay
      currentWaySel.state := AVAILABLE
    }elsewhen replace {
      currentWaySel.way := stepRsp @@ replaceWay
      currentWaySel.state := REPLACE
    }elsewhen noneLock {
      currentWaySel.way := stepRsp @@ auxiliaryReplaceWay
      currentWaySel.state := REPLACE
    }otherwise{
      currentWaySel.way := 0
      currentWaySel.state := FAIL
    }

    currentWaySel.tag := tags(currentWaySel.way.resized)

    val waySelFolder = Reg(WaySelFolder())
    val selCurrent = stepRsp===U(0) || currentWaySel.priority > waySelFolder.priority
    when(io.tagRamBus.readRsp.fire && selCurrent){
      waySelFolder := currentWaySel
    }
  }

  val fsm = new StateMachine {
    val idle = new State with EntryPoint
    val foldCmd = new State
    val foldRsp = new State
    val foldLast = new State
    val rsp = new State

    idle
      .whenIsActive{
        when(metaSpike.valid)( goto(foldCmd) )
      }
    foldCmd
      .onEntry{
        metaSpike.ready := True
        spikeBuffer := metaSpike.payload
      }
      .whenIsActive{
        when(stepCmd.willOverflow){
          goto(foldRsp)
        }
        io.tagRamBus.readCmd.valid := True
      }
    foldRsp
      .whenIsActive{
        when(stepRsp.willOverflow){
          goto(foldLast)
        }
      }
    foldLast
      .whenIsActive{
        goto(rsp)
      }
    rsp
      .whenIsActive{
        when(rspEvent.ready){
          when(metaSpike.valid)( goto(foldCmd) )
            .otherwise( goto(idle) )
        }
        rspEvent.valid := True
      }
  }

  val (toTagUpdateSpike, toReadySpike, toMissSpike, toRollBackSpike) = {
    import tagDetect.waySelFolder
    val ret = StreamFork(rspEvent, 4)
    (
      ret(0).throwWhen(waySelFolder.isRollBackState),
      ret(1).takeWhen(waySelFolder.state===TagState.HIT),
      ret(2).takeWhen(waySelFolder.isMissState),
      ret(3).takeWhen(waySelFolder.isRollBackState)
    )
  }

  val outputAssign = new Area {
    import tagDetect.waySelFolder
    val cacheAddressBase = (spikeBuffer.nid(setIndexRange) @@ waySelFolder.way) << cacheLineAddrWidth

    io.missSpike << toMissSpike.translateWith{
      val ret = new MissSpike
      ret.assignSomeByName(spikeBuffer)
      ret.cacheAddressBase := cacheAddressBase
      ret.tagState := waySelFolder.state
      ret.cover := !waySelFolder.tag.dirty
      ret.replaceNid := waySelFolder.tag.tag @@ ret.nid(setIndexRange)
      ret
    }

    io.readySpike << toReadySpike.translateWith{
      val ret = ReadySpike()
      ret.assignSomeByName(spikeBuffer)
      ret.cacheAddressBase := cacheAddressBase
      ret
    }

    io.tagRamBus.writeCmd << toTagUpdateSpike.translateWith{
      val newTag = Tag()
      newTag.lock := True
      newTag.valid := True
      newTag.dirty := waySelFolder.tag.dirty
      newTag.tag := spikeBuffer.tag
      newTag.thread := spikeBuffer.thread
      newTag.timeStamp := spikeBuffer.tagTimeStamp
      val ret = cloneOf(io.tagRamBus.writeCmd)
      ret.setAddress(waySelFolder.way, spikeBuffer.nid(setIndexRange))
      ret.setMask(waySelFolder.way)
      ret.tags := Vec(Seq.fill(wayCountPerStep)(newTag))
      ret
    }

    io.rollBackSpike << toRollBackSpike.translateWith{
      val ret = MetaSpikeT()
      ret.assignAllByName(spikeBuffer)
      ret
    }

    io.idle := fsm.isActive(fsm.idle)
  }
}

trait SpikeCacheData extends Bundle {
  val data = Bits(busDataWidth bits)
}

case class MetaSpikeWithData() extends MetaSpike with SpikeCacheData
case class MissSpikeWithData() extends MissSpike with SpikeCacheData{
  val replaceSpike = new MetaSpike
  // use replaceSpike.nid instead of replaceNid, replaceNid set 0
}

class MissSpikeCtrl extends Component {
  val axi4Config = cacheAxi4Config

  val io = new Bundle {
    val writeBackSpikeData = master(Stream(Fragment(MetaSpikeWithData())))
    val missSpikeData = slave(Stream(Fragment(MissSpikeWithData())))
    val readySpike = master(Stream(ReadySpike()))
    val cache = master(Axi4(axi4Config))
  }

  when(io.missSpikeData.valid){
    assert(
      io.missSpikeData.cover || io.missSpikeData.tagState=/=TagState.AVAILABLE,
      message = L"available tagState without cover is illegal, nid:${io.missSpikeData.nid}"
    )
  }

  val spikeFork = new Area{
    private val missSpikeFork = StreamFork2(io.missSpikeData)
    private val dataFifo = missSpikeFork._1.queue(cacheReadDelay+2) // 1 for fifo, 1 for RAW counters update
    private val writeDataSrc  = StreamDemux(dataFifo, dataFifo.cover.asUInt, 2)
    private val busHeaderSrc  = StreamFork(missSpikeFork._2.takeWhen(missSpikeFork._2.isFirst), 4, synchronous = true)

    val spikeReadQueue = busHeaderSrc(0).throwWhen(busHeaderSrc(0).cover).queue(2)
    val spikeWriteQueue = busHeaderSrc(1).queue(2)
    val writeHeader = busHeaderSrc(2)
    val readHeader = busHeaderSrc(3).throwWhen(busHeaderSrc(3).cover)

    val replaceWrite = writeDataSrc(0)
    val coverWrite = writeDataSrc(1)

    val readRspLsatFire = io.cache.readRsp.fire && io.cache.readRsp.last
    spikeReadQueue.ready := readRspLsatFire
  }

  val replaceRawConstrain = new Area {
    import spikeFork._
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

    val fsm = new StateMachine{
      val underConstrain = new State with EntryPoint
      val writeOver = new State
      val readOver = new State

      underConstrain
        .whenIsActive{
          switch(replaceWrite.lastFire ## readRspLsatFire){
            is(B"01"){ goto(readOver) }
            is(B"10"){ goto(writeOver)}
          }
        }
      writeOver
        .whenIsActive{
          when(readRspLsatFire) { goto(underConstrain) }
        }
      readOver
        .whenIsActive{
          when(replaceWrite.lastFire){ goto(underConstrain) }
        }
    }
    val output = replaceWrite.haltWhen(
      fsm.isActive(fsm.writeOver) ||
        (fsm.isActive(fsm.underConstrain) && readRspCounter===writeDataCounter)
    )
    val haltReadRsp = fsm.isActive(fsm.readOver) && io.cache.readRsp.last
  }

  val axi = new Area {
    import spikeFork._

    io.cache.readCmd.arbitrationFrom(readHeader)
    io.cache.readCmd.addr := readHeader.cacheAddressBase
    io.cache.readCmd.size := Axi4.size.BYTE_8.asUInt
    io.cache.readCmd.burst := Axi4.burst.INCR
    io.cache.readCmd.len := readHeader.replaceSpike.len.resized
    if(axi4Config.useId) io.cache.readCmd.id := 0

    io.cache.writeCmd.arbitrationFrom(writeHeader)
    io.cache.writeCmd.addr := writeHeader.cacheAddressBase
    io.cache.writeCmd.size := Axi4.size.BYTE_8.asUInt
    io.cache.writeCmd.burst := Axi4.burst.INCR
    io.cache.writeCmd.len := writeHeader.len.resized
    if(axi4Config.useId) io.cache.writeCmd.id := 0

    val writeData = StreamArbiterFactory.fragmentLock.on(
      Seq(replaceRawConstrain.output, coverWrite)
    )
    io.cache.writeData.arbitrationFrom(writeData)
    io.cache.writeData.data := writeData.data
    io.cache.writeData.last := writeData.last
    io.cache.writeData.strb := writeData.last ? writeData.lastByteMask | B(0xFF)
  }

  io.writeBackSpikeData << io.cache.readRsp.haltWhen(replaceRawConstrain.haltReadRsp).translateWith{
    import spikeFork.spikeReadQueue
    val ret = cloneOf(io.writeBackSpikeData.payload)
    ret.last := io.cache.readRsp.last
    ret.data := io.cache.readRsp.data
    ret.fragment.assignSomeByName(spikeReadQueue.replaceSpike)
    ret
  }

  val writeOver = new Area{
    import spikeFork.spikeWriteQueue
    val event = StreamJoin(io.cache.writeRsp, spikeWriteQueue)
    io.readySpike.arbitrationFrom(event)
    io.readySpike.payload.assignAllByName(spikeWriteQueue.fragment)
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
    val flush = slave(Stream(ThreadFlush()))
    val ssnClear = master(Stream(UInt(log2Up(threads) bits)))
    val notSpikeInPath = in Bool()
    val stallSpikePath = out Bool()
    val tagRamBus = master(TagRamBus())
    val flushSpike = master(Stream(Fragment(MissSpikeWithData())))
  }

  val tagReadAddress = Counter(tagRamAddressWidth bits, io.tagRamBus.readCmd.fire)
  val tagWriteAddress = Reg(UInt(tagRamAddressWidth bits))
  def tagsThreadMatchedOh(tags:Vec[Tag]):Vec[Bool] = Vec(tags.map(tag => tag.valid && tag.thread===io.flush.thread))

  when(RegNext(io.tagRamBus.readCmd.fire) init False){
    assert(
      io.tagRamBus.readRsp.valid,
      message = L"tagRam dont rsp immediately at CacheFlushCtrl, writeReady:${io.tagRamBus.writeCmd.ready}"
    )
  }

  val fsm = new StateMachine{
    val idle = makeInstantEntry()
    val clearSsn = new State
    val stallSpike = new State
    val lockTags = new State
    val tagRead = new State
    val bufferRsp = new State
    val sendFlushSpike = new State
    val flushDone = new State

    val rspToLock = RegNext(this.isActive(lockTags))
    val rspToInvalidIsFree = Bool()
    io.flush.ready := False
    io.ssnClear.valid := False
    io.ssnClear.payload := io.flush.thread
    io.stallSpikePath := False
    io.tagRamBus.readCmd.valid := False
    io.tagRamBus.readCmd.payload := tagReadAddress

    idle
      .whenIsActive{
        when(io.flush.valid){ goto(clearSsn) }
      }
    clearSsn
      .whenIsActive{
        when(io.ssnClear.ready){ goto(stallSpike) }
        io.ssnClear.valid := True
      }
    stallSpike
      .whenIsActive{
        when(io.notSpikeInPath){ goto(lockTags) }
        io.stallSpikePath := True
      }
    lockTags
      .whenIsActive{
        when(tagReadAddress.willOverflow){ goto(tagRead) }
        io.stallSpikePath := True
        io.tagRamBus.readCmd.valid := True
        tagWriteAddress := tagReadAddress
      }
    tagRead
      .whenIsActive {
        when(tagReadAddress.willIncrement){ goto(bufferRsp)}
        io.tagRamBus.readCmd.valid := True
        tagWriteAddress := tagReadAddress
      }
    bufferRsp
      .whenIsActive{ goto(sendFlushSpike) }
    sendFlushSpike
      .whenIsActive{
        when(rspToInvalidIsFree){
          when(tagWriteAddress===tagWriteAddress.maxValue) {
            goto(flushDone)
          }otherwise{
            goto(tagRead)
          }
        }
      }
    flushDone
      .whenIsActive{
        goto(idle)
        io.flush.ready := True
      }
  }

  val readRspDe = new Area {
    val (toInvalid, toLock) = {
      val ret = StreamDemux(io.tagRamBus.readRsp, fsm.rspToLock.asUInt, 2)
      (ret(0).stage(), ret(1))
    }
    fsm.rspToInvalidIsFree := toInvalid.isFree
  }

  val lockTagRamWriteThreadMatched = readRspDe.toLock.translateWith{
    val ret = cloneOf(io.tagRamBus.writeCmd.payload)
    ret.address := tagWriteAddress
    for((tag, rspTag) <- ret.tags.zip(readRspDe.toLock.payload)){
      tag.lock := True
      tag.assignUnassignedByName(rspTag)
    }
    ret.wayMask := tagsThreadMatchedOh(readRspDe.toLock.payload).asBits
    ret
  }
  val lockTagRamWrite = lockTagRamWriteThreadMatched.takeWhen(lockTagRamWriteThreadMatched.wayMask.orR)

  case class TagWay() extends Bundle {
    val tag = Tag()
    val wayLow = UInt(log2Up(wayCountPerStep) bits)
  }
  val invalidSeqOut = StreamArbiterFactory.on(
    StreamFork(readRspDe.toInvalid, wayCountPerStep)
      .zipWithIndex
      .map{case (s, wayLow) =>
        val tag = s.payload(wayLow)
        s.takeWhen(tag.valid && tag.thread===io.flush.thread).translateWith {
          val ret = TagWay()
          ret.tag := tag
          ret.wayLow := wayLow
          ret
        }
      }
  )

  val (invalidToTagRam, invalidToCache) = StreamFork2(invalidSeqOut)

  val invalidTagRamWrite = invalidToTagRam.translateWith {
    val ret = cloneOf(io.tagRamBus.writeCmd.payload)
    ret.address := tagWriteAddress
    ret.tags := ret.tags.getZero
    ret.wayMask := UIntToOh(invalidToTagRam.wayLow)
    ret
  }
  io.tagRamBus.writeCmd << StreamArbiterFactory.on(Seq(lockTagRamWrite, invalidTagRamWrite))
  io.flushSpike << invalidToCache.translateWith{
    val ret = cloneOf(io.flushSpike.fragment)
    ret.cacheAddressBase := (tagWriteAddress @@ invalidToCache.wayLow) << cacheLineAddrWidth
    ret.replaceSpike.len := io.flush.len
    ret.replaceSpike.lastWordMask := 1<<(busDataWidth/16)-1
    ret.replaceSpike.nid := invalidToCache.tag.tag @@ tagWriteAddress>>log2Up(tagStep)
    ret.tagState := TagState.REPLACE
    ret.assignUnassignedByName(ret.getZero)
    ret
  }.addFragmentLast(True).takeWhen(invalidToCache.tag.dirty)
}

class CacheDataCtrl extends Component {
  val io = new Bundle {
    val ackSpike = slave(Stream(AckSpike()))
    val metaSpike = slave(Stream(MetaSpikeT()))
    val missSpike = master(Stream(new MissSpike))
    val refractory = in UInt(tagTimeStampWidth bits)
    val flush = slave(Stream(ThreadFlush()))
    val writeBackSpikeData = master(Stream(Fragment(MetaSpikeWithData())))
    val missSpikeData = slave(Stream(Fragment(MissSpikeWithData())))
    val readySpike = master(Stream(ReadySpike()))
    val cache = master(Axi4(cacheAxi4Config))
  }

  val spikeTagFilter = new SpikeTagFilter
  val missSpikeCtrl = new MissSpikeCtrl
  val spikeOrderCtrl = new SpikeOrderCtrl
  val cacheFlushCtrl = new CacheFlushCtrl
  val rollBackSpikeFifo = StreamFifo(MetaSpikeT(), oooLengthMax-wayCount)

  io.metaSpike >> spikeOrderCtrl.io.sequentialInSpike
  io.ackSpike  >> spikeOrderCtrl.io.oooAckSpike

  val spikeWithSsn = StreamArbiterFactory.roundRobin.on(
    Seq(spikeOrderCtrl.io.metaSpikeWithSsn, rollBackSpikeFifo.io.pop)
  ).haltWhen(cacheFlushCtrl.io.stallSpikePath)

  spikeTagFilter.io.metaSpike     << spikeWithSsn
  spikeTagFilter.io.refractory    := io.refractory
  spikeTagFilter.io.missSpike     >> io.missSpike
  spikeTagFilter.io.rollBackSpike >> rollBackSpikeFifo.io.push

  val missSpikeData = StreamArbiterFactory.lowerFirst.on(
    Seq(cacheFlushCtrl.io.flushSpike, io.missSpikeData)
  )

  missSpikeCtrl.io.missSpikeData      << missSpikeData
  missSpikeCtrl.io.writeBackSpikeData >> io.writeBackSpikeData
  missSpikeCtrl.io.cache              <> io.cache

  io.readySpike << StreamArbiterFactory.roundRobin.on(
    Seq(spikeTagFilter.io.readySpike, missSpikeCtrl.io.readySpike)
  ).queue(cacheLines) //TODO: move this to readySpikeManagement if it's designed

  cacheFlushCtrl.io.flush          << io.flush
  cacheFlushCtrl.io.ssnClear       >> spikeOrderCtrl.io.ssnClear
  cacheFlushCtrl.io.notSpikeInPath := spikeTagFilter.io.idle

  val tagRam = Seq.fill(wayCountPerStep)(
    Mem(Tag(), cacheLines/wayCountPerStep)
  )

  val tagRamBusArbitration = new Area {
    val tagRamBusPrioritySeq = Seq(
      cacheFlushCtrl.io.tagRamBus,
      spikeOrderCtrl.io.tagRamBus,
      spikeTagFilter.io.tagRamBus
    )

    val writeCmd = StreamArbiterFactory.lowerFirst.on(
      tagRamBusPrioritySeq.map(_.writeCmd)
    ).toFlow
    for((tagRam, way) <- tagRam.zipWithIndex){
      tagRam.write(writeCmd.address, writeCmd.tags(way), writeCmd.wayMask(way))
    }

    val readCmd = StreamArbiterFactory.lowerFirst.on(tagRamBusPrioritySeq.map(_.readCmd))
    // use head StreamRead instead of fork join all StreamRead
    val streamReadRsp = tagRam.head.streamReadSync(readCmd)
    val rsp = streamReadRsp.translateWith{
      Vec(Seq(streamReadRsp.payload) ++ tagRam.tail.map(_.readSync(readCmd.payload, readCmd.valid)))
    }
    val readCmdLast = RegNextWhen(readCmd, readCmd.ready)
    val writeCmdLast = RegNext(writeCmd)
    val bypass = readCmdLast.payload === writeCmd.address && writeCmd.valid
    when(bypass){
      rsp.payload := writeCmdLast.tags
    }

    val readSel = RegNextWhen(OHToUInt(tagRamBusPrioritySeq.map(_.readCmd.fire)), rsp.ready)
    StreamDemux(rsp, readSel, tagRamBusPrioritySeq.length)
      .zip(tagRamBusPrioritySeq)
      .foreach{ case(a, b) => a>>b.readRsp }
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
/*
object Solution extends App{
  case class Bg(find:Boolean, shu:BigInt)
  def doBg(x:Int, y:Int): Bg={
    val shu = x.toBigInt * y.toBigInt
    val shuStr = shu.toString()
    Bg(shuStr==shuStr.reverse, shu)
  }
  def dp(nMax:Int, n:Int=0, shu:BigInt=0):BigInt = {
    if(n==nMax){
      shu
    }else{
      var bg = Bg(find = false, shu)
      val hh = 1<<n
      val hl = 1<<(n-1)
      for(y <- (hl until hh).reverse if !bg.find){
        for(x1 <- (y until hh).reverse if !bg.find){
          bg = doBg(x1, y)
        }
      }
      for(y <- (1 until hl).reverse if !bg.find){
        for(x2 <- (hl until hh).reverse if !bg.find){
          bg = doBg(x2, y)
        }
      }
    }
  }
  def largestPalindrome(n: Int): Int = {
    (dp(nMax = n) % 1337).toInt
  }
}
 */