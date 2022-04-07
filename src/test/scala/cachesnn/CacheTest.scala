package cachesnn

import cachesnn.Synapse.{cacheAxi4Config, cacheLenWidth, neuronSize, nidWidth}
import cachesnn.Cache._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.bmb.sim.BmbMemoryAgent
import spinal.lib.sim._

import scala.util.Random
object CacheTest {
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
  val axiMemConfig = AxiMemorySimConfig(
    maxOutstandingReads = 1,
    readResponseDelay = 5
  )

  test("cache fetch test"){
    complied.doSim(959817836){dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val cache = AxiMemorySim(dut.io.cache, dut.clockDomain, axiMemConfig)

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
                  nid:Int = 0,
                  thread:Int = 0,
                  timeStamp:Int = 0)

case class TagRamSim(bus:TagRamBus, clockDomain: ClockDomain) {
  private val size = tagStep*(1<<setIndexRange.size)
  val ram = Array.tabulate(size,wayCountPerStep)((_,_) =>TagSim())

  val (rspDriver, rspQueue) = StreamDriver.queue(bus.readRsp, clockDomain)
  StreamReadyRandomizer(bus.readCmd, clockDomain)
  StreamReadyRandomizer(bus.writeCmd, clockDomain)
  //bus.readCmd.ready #= true
  //bus.writeCmd.ready #= true
  StreamMonitor(bus.readCmd, clockDomain){ addr=>
    val rsp = ram(addr.toInt)
    rspQueue.enqueue{tags=>
      tags.zip(rsp).foreach{case(tag, tagSim)=>
        tag.nid #= tagSim.nid
        tag.valid #= tagSim.valid
        tag.thread #= tagSim.thread
        tag.lock #= tagSim.lock
        tag.timeStamp #= tagSim.timeStamp
      }
    }
  }
  StreamMonitor(bus.writeCmd, clockDomain){ cmd=>
    val tagSims = ram(cmd.address.toInt)
    for((tagSim, way) <- tagSims.zipWithIndex){
      val wayValid = ((cmd.wayMask.toInt>>way)&0x1)==1
      if(wayValid){
        ram(cmd.address.toInt)(way) = TagSim(
          nid = cmd.tags(way).nid.toInt,
          valid = cmd.tags(way).valid.toBoolean,
          thread = cmd.tags(way).thread.toInt,
          lock = cmd.tags(way).lock.toBoolean,
          timeStamp= cmd.tags(way).timeStamp.toInt
        )
      }
    }
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
  def fillSpike(nidSeq:Seq[Int]): Unit ={
    val setIdMask = (1<<setIndexRange.size)-1
    val setIdOffset = log2Up(tagStep)
    val wayIdOffset = log2Up(wayCountPerStep)
    val wayMask = (1<<wayIdOffset)-1
    val wayOccupancy = Array.fill(cacheLines/wayCount)(0)
    for(nid <- nidSeq){
      val setId = nid & setIdMask
      val way = wayOccupancy(setId)
      val wayId = way & wayMask
      val address = (setId<<setIdOffset) | (way>>wayIdOffset)
      ram(address)(wayId) = TagSim(nid = nid, valid = true)
      wayOccupancy(setId) += 1
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
  def wayOccupancy = {
    ram.map(
      _.map(_.valid.toInt).sum
    ).grouped(tagStep).map(_.sum).toArray
  }
}

case class SpikeDriver[T<:Spike](port:Stream[T], clockDomain: ClockDomain){
  private val (driver, spikeQueue) = StreamDriver.queue(port, clockDomain)
  driver.transactionDelay = ()=>0
  private var timeStamp = 0

  def setTimeStamp(t:Int): Unit ={
    timeStamp = t
  }

  def send(nid:Int): Unit ={
    spikeQueue.enqueue { spike =>
      spike.nid #= nid
      spike match {
        case metaSpikeT: MetaSpikeT => metaSpikeT.tagTimeStamp #= timeStamp
        //case metaSpike:MetaSpike =>
      }
    }
  }
  def send(nidSeq:Seq[Int]): Unit ={
    for(nid <- nidSeq){
      send(nid)
    }
  }
}

class SpikeTagFilterTest extends AnyFunSuite {
  val compiled = SimConfig.withWave.compile(new SpikeTagFilter)
  val compiledWithInnerFifo = SimConfig.compile{
    val ret = new SpikeTagFilter
    ret.spikeRollBackFifo.io.simPublic()
    ret
  }

  case class TagRamDrivers(dut:SpikeTagFilter){
    val tagRam = TagRamSim(dut.io.tagRamBus, dut.clockDomain)
    val spikeDriver = SpikeDriver(dut.io.metaSpike, dut.clockDomain)
    StreamReadyRandomizer(dut.io.missSpike, dut.clockDomain)
    StreamReadyRandomizer(dut.io.readySpike, dut.clockDomain)
    dut.io.refractory #= 1
    private val nidBase = Random.nextInt(neuronSize-cacheLines)
    val spikes = nidBase until nidBase+cacheLines

    def assertHit(nid:Int): Unit ={
      dut.clockDomain.waitSamplingWhere(
        dut.io.readySpike.valid.toBoolean && dut.io.readySpike.ready.toBoolean
      )
      assert(dut.io.readySpike.nid.toInt==nid)
    }
    def assertHit(spikes:Seq[Int]): Unit ={
      for(nid <- spikes){
        assertHit(nid)
      }
    }
    def assertMiss(nid:Int, tagState:TagState.E): Unit ={
      dut.clockDomain.waitSamplingWhere(
        dut.io.missSpike.valid.toBoolean && dut.io.missSpike.ready.toBoolean
      )
      assert(dut.io.missSpike.nid.toInt==nid)
      assert(dut.io.missSpike.tagState.toEnum == tagState)
    }
    def assertMiss(spikes:Seq[Int], tagState:TagState.E): Unit ={
      for(nid <- spikes){
        assertMiss(nid, tagState)
      }
    }
    def assertReplace(spikes:Seq[Int]): Unit ={
      assertMiss(spikes, TagState.REPLACE)
    }
    def assertAvailable(spikes:Seq[Int]): Unit ={
      assertMiss(spikes, TagState.AVAILABLE)
    }
  }

  test("all compulsory conflict") {
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val driver = TagRamDrivers(dut)
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
        val driver = TagRamDrivers(dut)
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
      val driver = TagRamDrivers(dut)
      import driver.{tagRam, spikeDriver, spikes}
      tagRam.fillSpike(spikes)
      // force replace half under timeStamp equal
      val replaceSpikes = spikes.take(spikes.length/2).map(nid => (nid+cacheLines)%neuronSize)
      val noReplaceSpikes = spikes.drop(spikes.length/2)
      spikeDriver.setTimeStamp(0)
      spikeDriver.send(replaceSpikes)
      driver.assertReplace(replaceSpikes)

      // test replaced spikes
      tagRam.clearLock()
      spikeDriver.setTimeStamp(1)
      spikeDriver.send(replaceSpikes)
      driver.assertHit(replaceSpikes)

      // test no replaced spikes
      tagRam.clearLock()
      spikeDriver.setTimeStamp(2)
      spikeDriver.send(noReplaceSpikes)
      driver.assertHit(noReplaceSpikes)

      // now the replace spikes with timestamp 1
      // the no replace spikes with timestamp 2
      // follow re replace spikes will take place replace spikes, default refractory is 1
      tagRam.clearLock()
      spikeDriver.setTimeStamp(3)
      val reReplaceSpikes = spikes.take(spikes.length/2)
      spikeDriver.send(reReplaceSpikes)
      driver.assertReplace(reReplaceSpikes)
    }
  }

  test("lock test"){
    compiledWithInnerFifo.doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val driver = TagRamDrivers(dut)
      import driver.{tagRam, spikeDriver, spikes}
      tagRam.fillSpike(spikes)
      tagRam.setLock()
      spikeDriver.send(spikes)
      dut.clockDomain.waitSamplingWhere(
        !dut.spikeRollBackFifo.io.push.ready.toBoolean
      )
      tagRam.clearLock()
      // rsp nid is out of order
      for(_ <- spikes){
        dut.clockDomain.waitSamplingWhere(
          dut.io.readySpike.valid.toBoolean && dut.io.readySpike.ready.toBoolean
        )
      }
    }
  }
  test("fail test"){
    compiledWithInnerFifo.doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val driver = TagRamDrivers(dut)
      import driver.{tagRam, spikeDriver, spikes}
      tagRam.fillSpike(spikes)
      tagRam.setLock()
      val failSpikes = spikes.map(nid => (nid+cacheLines)%neuronSize)
      spikeDriver.send(failSpikes)
      dut.clockDomain.waitSamplingWhere(
        !dut.spikeRollBackFifo.io.push.ready.toBoolean
      )
      tagRam.clearLock()
      // rsp nid is out of order
      for(_ <- spikes){
        dut.clockDomain.waitSamplingWhere(
          dut.io.missSpike.valid.toBoolean && dut.io.missSpike.ready.toBoolean
        )
        assert(dut.io.missSpike.tagState.toEnum==TagState.REPLACE)
      }
    }
  }
  test("cache address test"){
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(2)// all tags are locked
      SimTimeout(100000)
      val driver = TagRamDrivers(dut)
      import driver.{tagRam, spikeDriver}

      // sequential spikes are allocated with sequential cache address
      val sequentialSpikes = (0 until cacheLines)
      val setOffset = log2Up(wayCount)
      val cacheLineOffset = log2Up(cacheLineSize)
      val addressSeq = Seq.tabulate(wayCount, 1<<setIndexRange.size){(way,set)=>
        (set<<setOffset | way)<<cacheLineOffset
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