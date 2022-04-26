package cachesnn

import cachesnn.Synapse.{GlobalAddress, busDataWidth, cacheAxi4Config, cacheLenMax, neuronSize, threads}
import cachesnn.Cache._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.bmb.sim.BmbMemoryAgent
import spinal.lib.sim._

import scala.collection.mutable
import scala.util.Random

object CacheTest {
  val axiMemConfig = AxiMemorySimConfig(
    maxOutstandingReads = 1,
    writeResponseDelay = 2,
    readResponseDelay = 5
  )
}

class BankTest extends AnyFunSuite {

  class UramBankInst extends Component {
    def dataType = Bits(64 bits)
    def addressWidth = 12

    val io = new Bundle {
      val r = slave(MemReadPortStream(dataType, addressWidth))
      val w = slave(Stream(MemWritePort(dataType, addressWidth)))
    }
    val bank = UramBank(dataType, 1<<addressWidth)
    val linkedRsp = bank.streamReadSync(io.r.cmd, B"010")
    io.r.rsp << linkedRsp.translateWith(linkedRsp.value)
    io.w.toFlow >> bank.writePort
  }

  def write(dut:UramBankInst, n:Int): Unit ={
    dut.io.r.cmd.valid #= false
    dut.io.w.valid #= true
    dut.io.w.mask #= 0xFF
    for(i <- 0 until n){
      dut.io.w.address #= i
      dut.io.w.data #= i
      dut.clockDomain.waitRisingEdge()
    }
    dut.io.w.valid #= false
  }

  def read(dut:UramBankInst, n:Int): Unit ={
    dut.io.r.cmd.valid #= true
    for(i <- 0 until n){
      dut.io.r.cmd.payload #= i
      dut.clockDomain.waitSamplingWhere(dut.io.r.cmd.ready.toBoolean)
    }
    dut.io.r.cmd.valid #= false
  }

  test("access test"){
    SimConfig.compile(new UramBankInst).doSim{dut=>
      dut.clockDomain.forkStimulus(2)
      dut.io.r.rsp.ready #= true
      fork {write(dut, 1024)}
      dut.clockDomain.waitRisingEdge()
      fork {read(dut, 1024)}

      dut.clockDomain.waitSamplingWhere(
        dut.io.r.rsp.valid.toBoolean
      )
      for(i <- 0 until 1024){
        assert(i==dut.io.r.rsp.payload.toBigInt)
        assert(dut.io.r.rsp.valid.toBoolean)
        dut.clockDomain.waitRisingEdge()
      }
    }
  }

  test("back pressure"){
    SimConfig.compile(new UramBankInst).doSim{dut=>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(10000)
      dut.io.r.rsp.ready #= true
      fork {write(dut, 40)}
      dut.clockDomain.waitRisingEdge()
      fork {read(dut, 40)}

      val rspMonitor = fork {
        for(i <- 0 until 40){
          dut.clockDomain.waitSamplingWhere(
           dut.io.r.rsp.valid.toBoolean && dut.io.r.rsp.ready.toBoolean
          )
          assert(i==dut.io.r.rsp.payload.toBigInt)
        }
      }

      dut.clockDomain.waitRisingEdge(10)
      dut.io.r.rsp.ready #= false
      dut.clockDomain.waitRisingEdge(20)
      dut.io.r.rsp.ready #= true
      rspMonitor.join()
    }
  }
}

class Axi4UramBankTest extends AnyFunSuite {
  val complied = SimConfig.withWave.compile(Axi4UramBank(64, 256 KiB, 2))

  test("write"){
    complied.doSim{dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(10000)
      dut.io.axi.writeRsp.ready #= true
      dut.io.axi.writeCmd.addr #= 0
      dut.io.axi.writeCmd.valid #= true
      dut.io.axi.writeCmd.len #= 255
      dut.io.axi.writeCmd.burst #= "01".asBin
      dut.io.axi.writeCmd.size #= "011".asBin
      dut.io.axi.writeData.valid #= true
      dut.io.axi.writeData.data #= 666
      dut.io.axi.writeData.last #= false
      for(i <- 0 until 255){
        dut.clockDomain.waitSamplingWhere(
          dut.io.axi.writeData.ready.toBoolean
        )
      }
      dut.io.axi.writeData.last #= true
      dut.clockDomain.waitSamplingWhere(
        dut.io.axi.writeData.ready.toBoolean
      )
      dut.io.axi.writeData.last #= false
      dut.clockDomain.waitSamplingWhere(
        dut.io.axi.writeCmd.ready.toBoolean
      )
      dut.io.axi.writeCmd.addr #= 4096
      dut.clockDomain.waitSamplingWhere(
        dut.io.axi.writeCmd.ready.toBoolean
      )
      dut.clockDomain.waitRisingEdge()
    }
  }
  test("read"){
    complied.doSim{dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(10000)
      dut.io.axi.readRsp.ready #= true
      dut.io.axi.readCmd.valid #= true
      dut.io.axi.readCmd.addr #= 0
      dut.io.axi.readCmd.valid #= true
      dut.io.axi.readCmd.len #= 255
      dut.io.axi.readCmd.burst #= "01".asBin
      dut.io.axi.readCmd.size #= "011".asBin
      dut.clockDomain.waitSamplingWhere(
        dut.io.axi.readRsp.valid.toBoolean && dut.io.axi.readRsp.last.toBoolean
      )
    }
  }
}

class CacheDataPackerTest extends AnyFunSuite {

  class CacheDataPackerAxi4Wrapper extends Component {
    val inst = new CacheDataPacker
    val io = new Bundle {
      val preSpike = slave(Stream(ReadySpike()))
      val cache = master(Axi4(cacheAxi4Config))
      val output = master(Stream(Fragment(new SynapseData)))
    }
    io.preSpike >> inst.io.input
    io.output << inst.io.output
    io.cache <> inst.io.cache.toAxi4()
  }

  val complied = SimConfig.withWave.compile(new CacheDataPackerAxi4Wrapper)

  test("cache fetch test"){
    complied.doSim(959817836){dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      AxiMemorySim(dut.io.cache, dut.clockDomain, CacheTest.axiMemConfig)

      dut.io.output.ready #= true
      for(i <- 1 to 10){
        dut.io.preSpike.valid #= true
        dut.io.preSpike.nid #= i
        dut.io.preSpike.cacheAddressBase #= i*100
        dut.io.preSpike.len #= 99
        dut.io.preSpike.threadAddressBase #= 0
        dut.io.preSpike.dense #= true
        dut.clockDomain.waitSamplingWhere(
          dut.io.preSpike.ready.toBoolean
        )
      }
    }
  }
}

class SynapseEventPackerTest extends AnyFunSuite {
  val compiled = SimConfig.withWave.compile(new SynapseEventPacker)

  test("a test"){
    compiled.doSim{dut=>
      dut.clockDomain.forkStimulus(2)
      dut.io.output.ready #= true
      val bmbMem = new BmbMemoryAgent(8 KiB)
      bmbMem.addPort(dut.io.preSpikeTableBus, 0, dut.clockDomain, true, false)
      bmbMem.addPort(dut.io.postSpikeTableBus, 0, dut.clockDomain, true, false)
      for(addr <- 0 until (8 KiB).toInt){
        bmbMem.setByte(addr, addr.toByte)
      }
      val (driver, inputQueue) = StreamDriver.queue(dut.io.input, dut.clockDomain)
      driver.transactionDelay = () => 0
      for(i <- 0 until 20){
        inputQueue.enqueue{cmd =>
          cmd.last #= Random.nextInt(10)==1
          cmd.nid #= 100
          cmd.postNidBase #= i<<3
        }
      }
      dut.clockDomain.waitRisingEdge(50)
    }
  }
}

case class TagSim(valid:Boolean = false,
                  lock:Boolean = false,
                  dirty:Boolean = false,
                  tag:Int = 0,
                  thread:Int = 0,
                  timeStamp:Int = 0){
}

case class TagRamSim(bus:TagRamBus, clockDomain: ClockDomain) {
  private val size = tagStep*(1<<setIndexRange.size)
  val ram = Array.tabulate(size,wayCountPerStep)((_,_) =>TagSim())

  val (rspDriver, rspQueue) = StreamDriver.queue(bus.readRsp, clockDomain)
  rspDriver.transactionDelay = () => 0
  rspDriver.delay = 0
  bus.readCmd.ready #= true
  bus.readRsp.ready #= true
  // to return rsp at 1 clk, sampling on fall edge
  clockDomain.onFallingEdges{
    val ready = rspQueue.isEmpty || bus.readRsp.ready.toBoolean
    if(ready && bus.readCmd.valid.toBoolean){
      val rsp = ram(bus.readCmd.payload.toInt)
      rspQueue.enqueue{tags=>
        tags.zip(rsp).foreach{case(tag, tagSim)=>
          tag.tag #= tagSim.tag
          tag.valid #= tagSim.valid
          tag.dirty #= tagSim.dirty
          tag.thread #= tagSim.thread
          tag.lock #= tagSim.lock
          tag.timeStamp #= tagSim.timeStamp
        }
      }
    }
    bus.readCmd.ready #= ready
  }

  val writeReady = StreamReadyRandomizer(bus.writeCmd, clockDomain)
  bus.writeCmd.ready #= true

  StreamMonitor(bus.writeCmd, clockDomain){ cmd=>
    val tagSims = ram(cmd.address.toInt)
    for((_, way) <- tagSims.zipWithIndex){
      val wayValid = ((cmd.wayMask.toInt>>way)&0x1)==1
      if(wayValid){
        ram(cmd.address.toInt)(way) = TagSim(
          tag = cmd.tags(way).tag.toInt,
          valid = cmd.tags(way).valid.toBoolean,
          thread = cmd.tags(way).thread.toInt,
          lock = cmd.tags(way).lock.toBoolean,
          dirty = cmd.tags(way).dirty.toBoolean,
          timeStamp= cmd.tags(way).timeStamp.toInt
        )
      }
    }
  }

  def setAlwaysReady(): Unit ={
    writeReady.factor = Float.PositiveInfinity
  }

  def updateAll(op: TagSim => TagSim):Unit = {
    for(i <- 0 until size){
      for(j <- 0 until wayCountPerStep){
        ram(i)(j) = op(ram(i)(j))
      }
    }
  }
  def flush(): Unit ={
    updateAll(_=>TagSim())
  }
  def fillSpike(spikes:Seq[SpikeSim]): Seq[SpikeSim] ={
    val setIdMask = (1<<setIndexRange.size)-1
    val setIdOffset = log2Up(tagStep)
    val wayIdOffset = log2Up(wayCountPerStep)
    val wayMask = (1<<wayIdOffset)-1
    val wayOccupancy = Array.fill(cacheLines/wayCount)(0)
    spikes.map{ spike =>
      val setId = spike.nid & setIdMask
      val way = wayOccupancy(setId)
      val wayId = way & wayMask
      val address = (setId<<setIdOffset) | (way>>wayIdOffset)
      val tag = spike.nid>>setIndexRange.size
      ram(address)(wayId) = TagSim(tag = tag, valid = true, dirty =  spike.dirty, thread = spike.thread)
      wayOccupancy(setId) += 1
      spike.copy(cacheAddressBase = ((setId<<(setIdOffset+wayIdOffset))|way)<<cacheLineAddrWidth)
    }
  }
  def setValid(): Unit = {
    updateAll(_.copy(valid = true))
  }
  def setLock():Unit = {
    updateAll(_.copy(lock = true))
  }
  def clearLock(): Unit ={
    updateAll(_.copy(lock = false))
  }
  def setDirty(): Unit ={
    updateAll(_.copy(dirty = true))
  }
  def wayOccupancy = {
    ram.map(
      _.map(_.valid.toInt).sum
    ).grouped(tagStep).map(_.sum).toArray
  }
  def wayLocked = {
    ram.map(
      _.map(_.lock.toInt).sum
    ).grouped(tagStep).map(_.sum).toArray
  }
  def waitAWrite(): Unit ={
    if(bus.writeCmd.valid.toBoolean){
      clockDomain.waitSamplingWhere(bus.writeCmd.ready.toBoolean)
    }
  }
}

case class SpikeSim(nid:Int,
                    virtual:Boolean=false,
                    thread:Int=0,
                    ssn:Int=0,
                    dirty:Boolean=false,
                    len:Int = 0,
                    cacheAddressBase:Int = 0,
                    tagState: TagState.E = TagState.AVAILABLE,
                    cover:Boolean = false,
                    data:BigInt = 0,
                    replaceLen:Int = 0,
                    replaceNid:Int = 0
                   ){
  def pruneToReadySpike:SpikeSim = {
    val defaultSpike = SpikeSim(0)
    this.copy(
      dirty = defaultSpike.dirty,
      tagState = defaultSpike.tagState,
      cover = defaultSpike.cover,
      data = defaultSpike.data,
      replaceNid = defaultSpike.replaceNid,
      replaceLen = defaultSpike.replaceLen
    )
  }
  def dataSeq:Seq[BigInt] = (0 to len).map(_ + (nid.toLong<<32).toBigInt)
  def replaceBy(spike:SpikeSim): SpikeSim ={
    spike.copy(
      cacheAddressBase = cacheAddressBase,
      replaceNid = nid,
      replaceLen = len,
      tagState = TagState.REPLACE,
      cover = false
    )
  }
}
object SpikeSim{
  def apply[S<:Spike](spike:S): SpikeSim ={
    val spikeBase = SpikeSim(
      nid = spike.nid.toInt,
      virtual = spike.virtual.toBoolean,
      thread = spike.thread.toInt,
      ssn = spike.ssn.toInt
    )
    spike match {
      case readySpike:ReadySpike => spikeBase.copy(
        len = readySpike.len.toInt,
        cacheAddressBase = readySpike.cacheAddressBase.toInt
      )
      case _ => spikeBase
    }
  }
}

case class SpikeDriver[T<:Data](port:Stream[T], clockDomain: ClockDomain){
  private val (driver, spikeQueue) = StreamDriver.queue(port, clockDomain)
  driver.transactionDelay = ()=>0
  private var timeStamp = 0

  def setTimeStamp(t:Int): Unit ={
    timeStamp = t & ((1<<tagTimeStampWidth)-1)
  }

  def send[S<:Spike](spikeSim:SpikeSim): Unit ={
    for((data, i) <- spikeSim.dataSeq.zipWithIndex){
      spikeQueue.enqueue { s =>
        val spike = s match {
          case sd: Fragment[S] =>{
            sd.last #= i==spikeSim.len
            sd.fragment
          }
          case _ => s
        }

        spike match {
          case ss:S =>
            ss.nid #= spikeSim.nid
            ss.thread #= spikeSim.thread
            ss.ssn #= spikeSim.ssn
            ss.virtual #= spikeSim.virtual
        }

        spike match {
          case metaSpikeT: MetaSpikeT => metaSpikeT.tagTimeStamp #= timeStamp
          case ackSpike: AckSpike => ackSpike.dirty #= spikeSim.dirty
          case missSpike:MissSpikeWithData =>
            missSpike.len #= spikeSim.len
            missSpike.cacheAddressBase #= spikeSim.cacheAddressBase
            missSpike.tagState #= spikeSim.tagState
            missSpike.cover #= spikeSim.cover
            missSpike.data #= data
            missSpike.lastWordMask #= 0xF
            missSpike.replaceSpike.len #= spikeSim.replaceLen
            missSpike.replaceSpike.nid #= spikeSim.replaceNid
        }
      }
    }
  }
  def send(spikeSimSeq:Seq[SpikeSim]): Unit ={
    for(spikeSim <- spikeSimSeq){
      send(spikeSim)
    }
  }
  def waitSendOver(): Unit ={
    while(spikeQueue.nonEmpty){
      clockDomain.waitRisingEdge()
    }
  }
}

class SpikeTagFilterTest extends AnyFunSuite {
  val compiled = SimConfig.compile(new SpikeTagFilter)

  case class SpikeTagFilterDrivers(dut:SpikeTagFilter){
    val tagRam = TagRamSim(dut.io.tagRamBus, dut.clockDomain)
    val spikeDriver = SpikeDriver(dut.io.metaSpike, dut.clockDomain)
    StreamReadyRandomizer(dut.io.missSpike, dut.clockDomain)
    StreamReadyRandomizer(dut.io.readySpike, dut.clockDomain)
    StreamReadyRandomizer(dut.io.rollBackSpike, dut.clockDomain)
    dut.io.refractory #= 1

    private val nidBase = 0//Random.nextInt(neuronSize-cacheLines)
    val spikes = (nidBase until nidBase+cacheLines).map(nid => SpikeSim(nid))
    val conflictSpikes = spikes.map{spike =>
      spike.copy(nid = (spike.nid+cacheLines)%neuronSize)
    }

    def assertHit(spike:SpikeSim): Unit ={
      dut.clockDomain.waitSamplingWhere(
        dut.io.readySpike.valid.toBoolean && dut.io.readySpike.ready.toBoolean
      )
      assert(dut.io.readySpike.nid.toInt==spike.nid)
    }
    def assertHit(spikes:Seq[SpikeSim]): Unit ={
      for(spike <- spikes){
        assertHit(spike)
      }
    }
    def assertMiss(spike:SpikeSim, tagState:TagState.E): Unit ={
      dut.clockDomain.waitSamplingWhere(
        dut.io.missSpike.valid.toBoolean && dut.io.missSpike.ready.toBoolean
      )
      assert(dut.io.missSpike.nid.toInt==spike.nid)
      assert(dut.io.missSpike.tagState.toEnum == tagState)
    }
    def assertMiss(spikes:Seq[SpikeSim], tagState:TagState.E): Unit ={
      for(spike <- spikes){
        assertMiss(spike, tagState)
      }
    }
    def assertReplace(spikes:Seq[SpikeSim], replacedSpike:Seq[SpikeSim]): Unit ={
      fork{
        for(rs <- replacedSpike){
          dut.clockDomain.waitSamplingWhere(
            dut.io.missSpike.valid.toBoolean && dut.io.missSpike.ready.toBoolean
          )
          assert(dut.io.missSpike.replaceNid.toInt==rs.nid)
        }
      }
      assertMiss(spikes, TagState.REPLACE)
    }
    def assertAvailable(spikes:Seq[SpikeSim]): Unit ={
      assertMiss(spikes, TagState.AVAILABLE)
    }
    def assertRollBack(spike:SpikeSim): Unit ={
      dut.clockDomain.waitSamplingWhere(
        dut.io.rollBackSpike.valid.toBoolean && dut.io.rollBackSpike.ready.toBoolean
      )
      assert(dut.io.rollBackSpike.nid.toInt==spike.nid)
    }
    def assertRollBack(spikes:Seq[SpikeSim]): Unit ={
      for(spike <- spikes){
        assertRollBack(spike)
      }
    }
  }

  test("all compulsory conflict") {
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val driver = SpikeTagFilterDrivers(dut)
      import driver.{tagRam, spikeDriver, spikes}
      spikeDriver.send(spikes)
      // first time all spike are compulsory conflict
      driver.assertAvailable(spikes)
      // all tags should be occupied
      for ((occupancy, way) <- tagRam.wayOccupancy.zipWithIndex) {
        assert(occupancy == wayCount, s"way: $way occupancy:${tagRam.wayOccupancy.mkString(" ")}")
      }
    }
  }
  test("hit all test"){
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(2)// all tags are locked
        SimTimeout(100000)
        val driver = SpikeTagFilterDrivers(dut)
        import driver.{tagRam, spikeDriver, spikes}
        tagRam.fillSpike(spikes)
        spikeDriver.send(spikes)
        driver.assertHit(spikes)
    }
  }
  test("replace test"){
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(2)// all tags are locked
      SimTimeout(100000)
      val driver = SpikeTagFilterDrivers(dut)
      import driver.{tagRam, spikeDriver, spikes, conflictSpikes}
      tagRam.fillSpike(spikes)
      // force replace half under timeStamp equal
      val replaceSpikes = conflictSpikes.take(spikes.length/2)
      val noReplaceSpikes = spikes.drop(spikes.length/2)
      spikeDriver.setTimeStamp(0)
      spikeDriver.send(replaceSpikes)
      driver.assertReplace(replaceSpikes, spikes.take(spikes.length/2))

      // test replaced spikes
      tagRam.waitAWrite()
      tagRam.clearLock()
      spikeDriver.setTimeStamp(1)
      spikeDriver.send(replaceSpikes)
      driver.assertHit(replaceSpikes)

      // test no replaced spikes
      tagRam.waitAWrite()
      tagRam.clearLock()
      spikeDriver.setTimeStamp(2)
      spikeDriver.send(noReplaceSpikes)
      driver.assertHit(noReplaceSpikes)

      // now the replace spikes with timestamp 1
      // the no replace spikes with timestamp 2
      // follow re replace spikes will take place replace spikes, default refractory is 1
      tagRam.waitAWrite()
      tagRam.clearLock()
      spikeDriver.setTimeStamp(3)
      val reReplaceSpikes = spikes.take(spikes.length/2)
      spikeDriver.send(reReplaceSpikes)
      driver.assertReplace(reReplaceSpikes, replaceSpikes)
    }
  }
  test("dirty test"){
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(2)// all tags are locked
      SimTimeout(100000)
      val driver = SpikeTagFilterDrivers(dut)
      import driver.{tagRam, spikeDriver, spikes, conflictSpikes}
      val dirtySpikes = spikes.map(_.copy(dirty = Random.nextBoolean()))
      tagRam.fillSpike(dirtySpikes)
      spikeDriver.send(conflictSpikes)

      val dirtyAssertion = fork {
        for(dirty <- dirtySpikes.map(_.dirty)){
          dut.clockDomain.waitSamplingWhere(
            dut.io.missSpike.ready.toBoolean && dut.io.missSpike.valid.toBoolean
          )
          assert(dut.io.missSpike.cover.toBoolean != dirty)
        }
      }

      driver.assertReplace(conflictSpikes, dirtySpikes)
      dirtyAssertion.join()
    }
  }
  test("lock test"){
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val driver = SpikeTagFilterDrivers(dut)
      import driver.{tagRam, spikeDriver, spikes}
      tagRam.fillSpike(spikes)
      tagRam.setLock()
      spikeDriver.send(spikes)
      driver.assertRollBack(spikes)
    }
  }
  test("fail test"){
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val driver = SpikeTagFilterDrivers(dut)
      import driver.{tagRam, spikeDriver, spikes}
      tagRam.fillSpike(spikes)
      tagRam.setLock()
      val failSpikes = spikes.map{spike=>
        spike.copy(nid = (spike.nid+cacheLines)%neuronSize)
      }
      spikeDriver.send(failSpikes)
      driver.assertRollBack(failSpikes)
    }
  }
  test("cache address test"){
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(2)// all tags are locked
      SimTimeout(100000)
      val driver = SpikeTagFilterDrivers(dut)
      import driver.{tagRam, spikeDriver}

      // sequential spikes are allocated with sequential cache address
      val sequentialSpikes = (0 until cacheLines).map(nid => SpikeSim(nid))
      val setOffset = log2Up(wayCount)
      val addressSeq = Seq.tabulate(wayCount, 1<<setIndexRange.size){(way,set)=>
        (set<<setOffset | way)<<cacheLineAddrWidth
      }.flatten

      tagRam.fillSpike(sequentialSpikes)
      spikeDriver.send(sequentialSpikes)
      for((nid, address) <- sequentialSpikes.zip(addressSeq)){
        driver.assertHit(nid)
        assert(
          dut.io.readySpike.cacheAddressBase.toInt==address,
          s"${dut.io.readySpike.cacheAddressBase.toInt.hexString()} ${address.hexString()}"
        )
      }
    }
  }
}

class SpikeOrderCtrlTest extends AnyFunSuite {
  val compiled = SimConfig.withWave.compile(new SpikeOrderCtrl)

  case class SpikeOrderCtrlDrivers(dut:SpikeOrderCtrl){
    val tagRam = TagRamSim(dut.io.tagRamBus, dut.clockDomain)
    val inSpikeDriver = SpikeDriver(dut.io.sequentialInSpike, dut.clockDomain)
    val ackSpikeDriver = SpikeDriver(dut.io.oooAckSpike, dut.clockDomain)
    private val spikeMonitorQueue = Seq.fill(threads)(mutable.Queue[SpikeSim]())
    private val (minAckDelay, maxAckDelay) = (64, 2048)
    private val oooAckSpikeQueue = mutable.Queue[SpikeSim]()
    private val ackedSpikeQueue = mutable.Queue[SpikeSim]()
    private val targetSsn = Array.fill(threads)(0)

    StreamMonitor(dut.io.sequentialInSpike, dut.clockDomain){spike =>
      val thread = spike.thread.toInt
      spikeMonitorQueue(thread).enqueue(SpikeSim(spike))
    }
    dut.io.metaSpikeWithSsn.ready #= true
    StreamMonitor(dut.io.metaSpikeWithSsn, dut.clockDomain){spike=>
      val thread = spike.thread.toInt
      val targetSpike = spikeMonitorQueue(thread).dequeue()
      assert(spike.nid.toInt==targetSpike.nid)
      assert(spike.ssn.toInt==targetSsn(thread))
      oooAckSpikeQueue.enqueue(targetSpike.copy(ssn=targetSsn(thread)))
      val nextSsn = targetSsn(thread) + 1
      targetSsn(thread) = if(nextSsn==oooLengthMax) 0 else nextSsn
    }

    var getDelay = () => ((1-Random.nextGaussian())*(maxAckDelay-minAckDelay)).abs.ceil.toInt+minAckDelay-1

    case class AckSpike(spike:SpikeSim = SpikeSim(0), valid:Boolean=false)

    val ackThread = fork{
      var currentTime = 0
      val seqAckQueue = Array.fill(maxAckDelay)(AckSpike())
      while(true){
        dut.clockDomain.waitRisingEdge()
        currentTime += 1
        // send ack spike to dut
        val ackId = currentTime%maxAckDelay
        val ackSpike = seqAckQueue(ackId)
        if(ackSpike.valid){
          ackSpikeDriver.send(ackSpike.spike)
          seqAckQueue(ackId) = AckSpike()
        }

        // delay a spike for ack
        val delayedTime = (currentTime + getDelay()) % maxAckDelay
        var successDelay = false
        if(oooAckSpikeQueue.nonEmpty){
          val spikeForDelay = oooAckSpikeQueue.dequeue()
          for(i <- (delayedTime until maxAckDelay)++(0 until ackId) if !successDelay){
            if(!seqAckQueue(i).valid){
              seqAckQueue(i) = AckSpike(spikeForDelay, valid = true)
              successDelay = true
              ackedSpikeQueue.enqueue(spikeForDelay)
            }
          }
          assert(successDelay, s"try delay nid-${spikeForDelay.nid} ssn-${spikeForDelay.ssn} $delayedTime times")
        }
      }
    }

    def waitThreadDone(thread:Int): Unit ={
      dut.clockDomain.waitSamplingWhere(
        dut.io.threadDone(thread).toBoolean
      )
    }
  }

  test("ack snn in valid range assertion 1"){
    intercept[Throwable]{
      SimConfig.compile(new SpikeOrderCtrl).doSim{ dut =>
        dut.clockDomain.forkStimulus(2)
        SimTimeout(100000)
        val driver = SpikeOrderCtrlDrivers(dut)
        driver.ackThread.terminate()
        driver.inSpikeDriver.send(SpikeSim(1))
        driver.inSpikeDriver.waitSendOver()
        dut.clockDomain.waitRisingEdge()
        dut.io.oooAckSpike.valid #= true
        dut.io.oooAckSpike.thread #= 0
        dut.io.oooAckSpike.ssn #= 2
        dut.clockDomain.waitRisingEdge(2)
      }
    }
  }
  test("ack snn in valid range assertion 2"){
    intercept[Throwable]{
      SimConfig.compile(new SpikeOrderCtrl).doSim{ dut =>
        dut.clockDomain.forkStimulus(2)
        SimTimeout(100000)
        val driver = SpikeOrderCtrlDrivers(dut)
        driver.inSpikeDriver.send((0 until oooLengthMax/2).map(nid => SpikeSim(nid)))
        driver.inSpikeDriver.waitSendOver()
        driver.waitThreadDone(0)
        driver.inSpikeDriver.send((oooLengthMax/2 until oooLengthMax).map(nid => SpikeSim(nid)))
        driver.inSpikeDriver.waitSendOver()
        driver.ackSpikeDriver.send(SpikeSim(0, ssn = oooLengthMax/4))
        driver.ackSpikeDriver.waitSendOver()
        dut.clockDomain.waitRisingEdge(2)
      }
    }
  }
  test("illegal ack when rob empty"){
    intercept[Throwable]{
      SimConfig.compile(new SpikeOrderCtrl).doSim{ dut =>
        dut.clockDomain.forkStimulus(2)
        SimTimeout(100000)
        SpikeOrderCtrlDrivers(dut)
        dut.io.oooAckSpike.valid #= true
        dut.io.oooAckSpike.ssn #= 2
        dut.clockDomain.waitRisingEdge(2)
      }
    }
  }
  test("sequential ack test"){
    compiled.doSim{dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val driver = SpikeOrderCtrlDrivers(dut)
      driver.getDelay = () => 1
      import driver.inSpikeDriver
      val spikes = (0 until neuronSize).map(nid => SpikeSim(nid))
      inSpikeDriver.send(spikes)
      inSpikeDriver.waitSendOver()
      driver.waitThreadDone(0)
    }
  }
  test("ooo ack test"){
    compiled.doSim{dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val driver = SpikeOrderCtrlDrivers(dut)
      import driver.inSpikeDriver
      val spikes = (0 until neuronSize).map(nid => SpikeSim(nid))
      inSpikeDriver.send(spikes)
      inSpikeDriver.waitSendOver()
      driver.waitThreadDone(0)
    }
  }
  test("thread parallel test"){
    compiled.doSim{dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(10000000)
      val driver = SpikeOrderCtrlDrivers(dut)
      import driver.inSpikeDriver
      StreamReadyRandomizer(dut.io.metaSpikeWithSsn, dut.clockDomain)
      for(t <- 0 until 256){
        inSpikeDriver.setTimeStamp(t)
        for(thread <- 0 until threads){
          inSpikeDriver.setTimeStamp(t)
          val spikes = Seq.fill(Random.nextInt(256))(SpikeSim(Random.nextInt(neuronSize), thread = thread))
          inSpikeDriver.send(spikes)
          inSpikeDriver.waitSendOver()
        }
        for(thread <- 0 until threads){
          driver.waitThreadDone(thread)
        }
      }
    }
  }
}

class MissSpikeCtrlTest extends AnyFunSuite {

  case class MissSpikeCtrlMountCache() extends Component{
    val cache = Axi4UramBank(64, GlobalAddress.cache.size, 1)
    val inst = new MissSpikeCtrl
    val io = new Bundle {
      val writeBackSpikeData = master(Stream(Fragment(MetaSpikeWithData())))
      val missSpikeData = slave(Stream(Fragment(MissSpikeWithData())))
      val readySpike = master(Stream(ReadySpike()))
    }
    io.writeBackSpikeData << inst.io.writeBackSpikeData
    io.missSpikeData >> inst.io.missSpikeData
    io.readySpike << inst.io.readySpike
    inst.io.cache <> cache.io.axi
    inst.io.cache.simPublic()
  }

  val complied =  SimConfig.compile(MissSpikeCtrlMountCache())

  case class MissSpikeCtrlDriver(dut:MissSpikeCtrlMountCache){
    val spikeDriver = SpikeDriver(dut.io.missSpikeData, dut.clockDomain)

    val readySpikeMonitorQueue = mutable.Queue[SpikeSim]()
    // monitor check ready spike and RAW hazard, ignore checking the writeBackData
    dut.io.writeBackSpikeData.ready #= true
    dut.io.readySpike.ready #= true
    StreamMonitor(dut.io.readySpike, dut.clockDomain){spike=>
      val targetSpike = readySpikeMonitorQueue.dequeue()
      assert(targetSpike==SpikeSim(spike))
    }

    // no stuck assertion
    val noStuckAssertion = fork{
      while(true){
        dut.clockDomain.waitSamplingWhere(dut.io.missSpikeData.valid.toBoolean)
        if(!dut.io.missSpikeData.ready.toBoolean){
          dut.clockDomain.waitSampling()
          assert(dut.io.missSpikeData.ready.toBoolean)
        }
      }
    }
    def waitAllSpikesReady(): Unit ={
      while(readySpikeMonitorQueue.nonEmpty){
        dut.clockDomain.waitRisingEdge()
      }
    }
    def assertWriteBackSpikeData(replaceSpike: Seq[SpikeSim]): Unit ={
      for(s <- replaceSpike){
        assertWriteBackSpikeData(s)
      }
    }
    def assertWriteBackSpikeData(replaceSpike:SpikeSim): Unit ={
      for(data <- replaceSpike.dataSeq){
        dut.clockDomain.waitSamplingWhere(
          dut.io.writeBackSpikeData.valid.toBoolean && dut.io.writeBackSpikeData.ready.toBoolean
        )
        assert(
          dut.io.writeBackSpikeData.nid.toInt==replaceSpike.nid,
          f"${dut.io.writeBackSpikeData.nid.toInt}, ${replaceSpike.nid}"
        )
        assert(
          dut.io.writeBackSpikeData.len.toInt==replaceSpike.len,
          f"${dut.io.writeBackSpikeData.len.toInt}, ${replaceSpike.len}"
        )
        assert(
          dut.io.writeBackSpikeData.data.toBigInt==data,
          f"${dut.io.writeBackSpikeData.data.toBigInt.hexString()}, ${data.hexString()}"
        )
      }
    }
  }

  def randomLen = Random.nextInt(127-1) + 1 // avoid 0 which is flush spike
  def randomNid = Random.nextInt(neuronSize)

  test("exception: available with not cover"){
    intercept[Throwable]{
      SimConfig.compile(MissSpikeCtrlMountCache()).doSim{ dut =>
        dut.clockDomain.forkStimulus(2)
        dut.io.missSpikeData.valid #= true
        dut.io.missSpikeData.cover #= false
        dut.io.missSpikeData.tagState #= TagState.AVAILABLE
        dut.clockDomain.waitRisingEdge(2)
      }
    }
  }
  test("consist cover write"){
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val driver = MissSpikeCtrlDriver(dut)
      import driver.{spikeDriver, readySpikeMonitorQueue}

      val spikes = (0 until cacheLines).map{i=>
        SpikeSim(i, len = 64, cacheAddressBase = i<<cacheLineAddrWidth, tagState = TagState.AVAILABLE, cover = true)
      }
      spikeDriver.send(spikes)
      readySpikeMonitorQueue ++= spikes.map(_.pruneToReadySpike)
      driver.waitAllSpikesReady()
    }
  }
  test("consist replace"){
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val driver = MissSpikeCtrlDriver(dut)
      import driver.{spikeDriver, readySpikeMonitorQueue}
      driver.noStuckAssertion.terminate()
      val spikes = (0 until cacheLines).map{i=>
        // read len should shorter than write len this test to avoid stuck
        SpikeSim(i, len = 64, cacheAddressBase = i<<cacheLineAddrWidth, tagState = TagState.REPLACE, replaceLen = Random.nextInt(64))
      }
      spikeDriver.send(spikes)
      readySpikeMonitorQueue ++= spikes.map(_.pruneToReadySpike)
      driver.waitAllSpikesReady()
    }
  }
  test("mix test"){
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(1000000)
      val driver = MissSpikeCtrlDriver(dut)
      import driver.{spikeDriver, readySpikeMonitorQueue}

      val spikes = Seq.fill(neuronSize)(Random.nextInt(neuronSize)).map{i=>
        val len = 32 + Random.nextInt(64)  // too small burst len will trigger stall
        val spikeBase = SpikeSim(i, len = len, cacheAddressBase = (i%cacheLines)<<cacheLineAddrWidth, replaceLen = len)
        if(Random.nextBoolean()){
          spikeBase.copy(tagState = TagState.AVAILABLE, cover = true)
        }else{
          spikeBase.copy(tagState = TagState.REPLACE, cover = Random.nextBoolean())
        }
      }
      spikeDriver.send(spikes)
      readySpikeMonitorQueue ++= spikes.map(_.pruneToReadySpike)
      driver.waitAllSpikesReady()
    }
  }
  test("RAW hazard test"){
    complied.doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(10000000)
      val driver = MissSpikeCtrlDriver(dut)
      import driver.{spikeDriver, readySpikeMonitorQueue}
      driver.noStuckAssertion.terminate()
      StreamReadyRandomizer(dut.io.readySpike, dut.clockDomain)
      StreamReadyRandomizer(dut.io.writeBackSpikeData, dut.clockDomain)

      for(_ <- 0 until 16){
        val cacheAddress = Random.shuffle(
          (0 until cacheLines).map(_<<10)
        )
        val initSpike = cacheAddress.map( addr =>
          SpikeSim(randomNid, len = randomLen, cacheAddressBase = addr, tagState = TagState.AVAILABLE, cover = true)
        )
        spikeDriver.send(initSpike)
        readySpikeMonitorQueue ++= initSpike.map(_.pruneToReadySpike)

        val replaceSpike = initSpike.map(_.replaceBy(SpikeSim(nid = randomNid, len = randomLen)))
        spikeDriver.send(replaceSpike)
        readySpikeMonitorQueue ++= replaceSpike.map(_.pruneToReadySpike)
        driver.assertWriteBackSpikeData(initSpike)
      }
      driver.waitAllSpikesReady()
    }
  }
  test("flush spike test"){
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(1000000)
      val driver = MissSpikeCtrlDriver(dut)
      driver.noStuckAssertion.terminate()
      import driver.{spikeDriver, readySpikeMonitorQueue}

      val spikes = Seq.fill(neuronSize)(Random.nextInt(neuronSize)).map{i=>
        val spikeBase = SpikeSim(i, len = randomLen, cacheAddressBase = (i%cacheLines)<<cacheLineAddrWidth, replaceLen = randomLen)
        if(Random.nextBoolean()){
          spikeBase.copy(tagState = TagState.AVAILABLE, cover = true)
        }else{
          if(Random.nextBoolean()){
            spikeBase.copy(tagState = TagState.REPLACE, cover = Random.nextBoolean())
          }else{
            // flush spike
            spikeBase.copy(tagState = TagState.REPLACE, cover = false, len = 0)
          }
        }
      }
      spikeDriver.send(spikes)
      readySpikeMonitorQueue ++= spikes.filter(_.len!=0).map(_.pruneToReadySpike)
      driver.waitAllSpikesReady()
    }
  }
}

class CacheFlushCtrlTest extends AnyFunSuite {

  val complied = SimConfig.compile(new CacheFlushCtrl)

  case class CacheFlushCtrlDriver(dut:CacheFlushCtrl){
    val tagRam = TagRamSim(dut.io.tagRamBus, dut.clockDomain)
    tagRam.setAlwaysReady()
    fork{
      while(true){
        dut.clockDomain.waitRisingEdge(Random.nextInt(666))
        dut.io.notSpikeInPath #= Random.nextBoolean()
      }
    }
    StreamReadyRandomizer(dut.io.flushSpike, dut.clockDomain)

    val spikeQueue = mutable.Queue[SpikeSim]()
    StreamMonitor(dut.io.flushSpike, dut.clockDomain){ s =>
      val target = spikeQueue.dequeue()
      assert(s.last.toBoolean)
      assert(s.len.toInt==0)
      assert(s.replaceSpike.nid.toInt==target.nid)
      assert(s.cacheAddressBase.toInt==target.cacheAddressBase, f"${s.cacheAddressBase.toInt.hexString()} ${target.cacheAddressBase.hexString()}")
      assert(s.replaceSpike.len.toInt==target.len)
    }

    val flushLen = Random.nextInt(128)
    def flush(thread:Int): Unit ={
      fork{
        dut.io.flush.thread #= thread
        dut.io.flush.valid #= true
        dut.io.flush.len #= flushLen
        dut.clockDomain.waitRisingEdgeWhere(dut.io.flush.ready.toBoolean)
        dut.io.flush.valid #= false
      }
    }
    def monitorSpike(spike: SpikeSim): Unit ={
      spikeQueue.enqueue(spike)
    }
    def assertLock(n:Int): Unit ={
      dut.clockDomain.waitSamplingWhere(dut.io.stallSpikePath.toBoolean)
      dut.clockDomain.waitSamplingWhere(!dut.io.stallSpikePath.toBoolean)
      dut.clockDomain.waitSamplingWhere(
        !dut.io.tagRamBus.writeCmd.valid.toBoolean
      )
      assert(tagRam.wayLocked.sum==n, tagRam.wayLocked.mkString(","))
    }
  }

  def freeRunDut(dut:CacheFlushCtrl): Unit ={
    dut.io.tagRamBus.readCmd.ready #= true
    dut.io.tagRamBus.writeCmd.ready #= true
    dut.io.notSpikeInPath #= true
    dut.io.flushSpike.ready #= true
  }

  test("flush all"){
    complied.doSim{dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val driver = CacheFlushCtrlDriver(dut)
      import driver.{tagRam, flushLen, monitorSpike}
      val spikes = (0 until cacheLines).map{i=>
        SpikeSim(i, len = flushLen, dirty = true)
      }
      val spikeWithCacheAddr = tagRam.fillSpike(spikes)
      for(way <- 0 until wayCount){
        for(setId <- 0 until (1<<setIndexRange.size)){
          monitorSpike(spikeWithCacheAddr((setId*wayCount)|way))
        }
      }
      driver.flush(thread = 0)
      driver.assertLock(cacheLines)
      dut.clockDomain.waitSamplingWhere(dut.io.flush.ready.toBoolean)
      assert(tagRam.wayOccupancy.sum==0, tagRam.wayOccupancy.mkString(","))
    }
  }
  test("flush threads"){
    complied.doSim{dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val driver = CacheFlushCtrlDriver(dut)
      import driver.{tagRam, flushLen, monitorSpike}
      val spikes = (0 until cacheLines).map{i=>
        SpikeSim(i, len = flushLen, thread = Random.nextInt(threads), dirty = Random.nextBoolean())
      }

      val spikeWithCacheAddr = tagRam.fillSpike(spikes)
      for(thread <- 0 until threads){

        for(way <- 0 until wayCount){
          for(setId <- 0 until (1<<setIndexRange.size)){
            val spike = spikeWithCacheAddr((setId*wayCount)|way)
            if(spike.dirty && spike.thread==thread)
              monitorSpike(spike)
          }
        }
        driver.flush(thread)
        driver.assertLock(spikes.count(s => s.thread==thread))
        dut.clockDomain.waitSamplingWhere(dut.io.flush.ready.toBoolean)
      }
      assert(tagRam.wayOccupancy.sum==0, tagRam.wayOccupancy.mkString(","))
    }
  }
  test("flush tagAccess no stall assertion"){
    intercept[Throwable]{
      SimConfig.compile(new CacheFlushCtrl).doSim{dut =>
        dut.clockDomain.forkStimulus(2)
        val timeOut = 10000
        freeRunDut(dut)
        dut.io.flush.valid #= true
        dut.io.tagRamBus.readRsp.valid #= false
        for(_ <- 0 to timeOut
            if !(dut.io.tagRamBus.readCmd.valid.toBoolean && dut.io.tagRamBus.readCmd.ready.toBoolean)){
          dut.clockDomain.waitRisingEdge()
        }
        dut.clockDomain.waitRisingEdge(2)
      }
    }
  }
}