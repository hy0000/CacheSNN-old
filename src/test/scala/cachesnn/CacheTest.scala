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
  rspDriver.transactionDelay = () => 1
  StreamReadyRandomizer(bus.readCmd, clockDomain)
  StreamReadyRandomizer(bus.writeCmd, clockDomain)
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
        op(ram(i)(j))
      }
    }
  }

  def flush(): Unit ={
    updateAll(_=>TagSim())
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
}

class SpikeTagFilterTest extends AnyFunSuite {
  val compiled = SimConfig.withWave.compile(new SpikeTagFilter)

  test("hit all"){
    compiled.doSim(1807999387){ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(10000)
      val tagRam = TagRamSim(dut.io.tagRamBus, dut.clockDomain)
      dut.io.missSpike.ready #= true
      dut.io.refractory #= 1
      dut.io.readySpike.ready #= true

      val spikeDriver = SpikeDriver(dut.io.metaSpike, dut.clockDomain)
      val nidBase = 0//Random.nextInt(neuronSize-cacheLines)
      val nidEnd = nidBase+11
      for(nid <- nidBase until nidEnd){
        spikeDriver.send(nid)
      }
      // first time all spike are compulsory conflict
      for(nid <- nidBase until nidEnd){
        dut.clockDomain.waitSamplingWhere(dut.io.missSpike.valid.toBoolean)
        assert(dut.io.missSpike.nid.toInt==nid)
        assert(dut.io.missSpike.tagState.toEnum==TagState.AVAILABLE)
      }
      // all tags should be occupied
      for((occupancy, way) <- tagRam.wayOccupancy.zipWithIndex){
        assert(occupancy==wayCount, s"way: $way occupancy:${tagRam.wayOccupancy.mkString(" ")}")
      }
      tagRam.clearLock()
      for(nid <- nidBase until nidEnd){
        spikeDriver.send(nid)
      }
      // after set valid, all spike should hit
      for(nid <- nidBase until nidEnd){
        dut.clockDomain.waitSamplingWhere(dut.io.readySpike.valid.toBoolean)
        assert(dut.io.readySpike.nid.toInt==nid)
      }
    }
  }
  test("lock all"){

  }
  test("replace all"){

  }
}