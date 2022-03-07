package cachesnn

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.bus.bmb.sim.BmbDriver

import scala.collection.mutable
import scala.util.Random

object StdpTest {
  val spikesTableSize = 4 KiB
  val bmbParameter = BmbParameter(
    addressWidth = 12,
    dataWidth = 32,
    sourceWidth = 2,
    contextWidth = 1,
    lengthWidth = 8,
    alignment = BmbParameter.BurstAlignement.WORD
  )
  val maxAddress = (1<<bmbParameter.access.addressWidth)-1
  val addressRange = Range(0,maxAddress, bmbParameter.access.byteCount)
  val spikesLength = 16
  val spikesCount = bmbParameter.access.dataWidth/spikesLength
  val neuronIdLowWidth = log2Up(spikesCount)
  val CONTEXT_UPDATE, CONTEXT_INSERT = 1
}

case class SpikeQueryCmdDriver(cmd: Stream[SpikeQueryEvent], clockDomain: ClockDomain) {
  val (_, cmdQueue) = StreamDriver.queue(cmd, clockDomain)

  def query(neuronId: Int, virtualSpike:Boolean=false): Unit ={
    cmdQueue.enqueue{cmd=>
      cmd.neuronId #= neuronId
      if(cmd.virtualSpike!=null){
        cmd.virtualSpike #= virtualSpike
      }
    }
  }
}

case class SpikesRspMonitor[T<:SpikesRsp](rsp: Stream[T], clockDomain: ClockDomain){
  val callBack = new mutable.Queue[T=>Unit]()
  StreamReadyRandomizer(rsp, clockDomain)
  StreamMonitor(rsp, clockDomain){r=>
    callBack.dequeue()(r)
  }

  def addCallBack(spikes:Int, neuronId:Int): Unit ={
    callBack.enqueue{r =>
      assert(r.neuronId.toInt==neuronId)
      assert(
        spikes==r.spikes.toBigInt,
        message = s"target:${spikes.hexString()} rsp:${r.spikes.toBigInt.hexString()}"
      )
    }
  }

  def addCallBacks(spikes:Seq[Int], neuronIds:Seq[Int]): Unit ={
    for((s, n)<- spikes.zip(neuronIds)){
      addCallBack(s, n)
    }
  }

  def waitComplete(): Unit ={
    clockDomain.waitRisingEdgeWhere(callBack.isEmpty)
  }
}

class SpikeTableTest extends AnyFunSuite {
  import StdpTest._

  val preSpikeTable = SimConfig
    .compile(new SpikesTable(bmbParameter, spikesTableSize, false))
  val postSpikeTable = SimConfig
    .compile(new SpikesTable(bmbParameter, spikesTableSize, true))
  val complied = Seq(preSpikeTable, postSpikeTable)

  def busWrite(bmbDriver: BmbDriver, data:Seq[BigInt]):Unit = {
    bmbDriver.ctrl.cmd.context #= 0
    for((address, spikes) <- addressRange.zip(data)){
      bmbDriver.write(spikes, address)
    }
  }
  def busRead(bmbDriver: BmbDriver, targetData:Seq[BigInt]):Unit = {
    bmbDriver.ctrl.cmd.context #= 0
    for((address, spikes) <- addressRange.zip(targetData)){
      val spikesReadByBus = bmbDriver.read(address)
      assert(
        spikes==spikesReadByBus,
        s"target:${spikes.hexString()}, readout:${spikesReadByBus.hexString()} "
      )
    }
  }
  def busUpdate(bmbDriver: BmbDriver):Unit = {
    bmbDriver.ctrl.cmd.context #= CONTEXT_UPDATE
    for(address <- addressRange){
      bmbDriver.read(address)
    }
  }
  def spikeQuery(driver: SpikeQueryCmdDriver,
                 neuronIds:Seq[Int],
                 virtualSpike:Boolean = false):Unit = {
    for(neuronId <- neuronIds){
      driver.cmdQueue.enqueue{ cmd=>
        cmd.neuronId #= neuronId
        if(cmd.virtualSpike!=null){
          cmd.virtualSpike #= virtualSpike
        }
      }
    }
  }
  def postSpikeInsert(bmbDriver: BmbDriver, neuronIds:Seq[Int]):Unit = {
    bmbDriver.ctrl.cmd.context #= CONTEXT_INSERT
    for(neuronId <- neuronIds){
      bmbDriver.write(0, neuronId)
    }
  }
  def genRandomSpikes():Seq[Int] = {
    val neuronIdUpBound = maxAddress/spikesCount + 1
    Seq.fill(1024)(Random.nextInt(neuronIdUpBound))
  }

  class SpikesTableSim(size:BigInt){

    val mask = (0x1<<spikesLength)-1
    var spikes = Seq.fill((size/spikesCount).toInt)(
      Random.nextInt() & mask
    )

    def update(): Unit ={
      spikes = spikes.map(s=>(s<<1)&mask)
    }
    def spikesInsert(neuronIds:Seq[Int], isPost:Boolean=false): Unit ={
      val mask = if(isPost) 0x2 else 0x1
      for(neuronId <- neuronIds){
        val newSpike = spikes(neuronId) | mask
        spikes = spikes.updated(
          neuronId, newSpike
        )
      }
    }
    def busFormatSpikes:Seq[BigInt] = {
      spikes.grouped(spikesCount).map{ss=>
        ss.zipWithIndex.map{ case (s, offset) =>
          s.toBigInt<<(offset*spikesLength)
        }.reduce(_|_)
      }.toSeq
    }
    def filter(neuronIds:Seq[Int]):Seq[Int] = {
      neuronIds.map(spikes(_))
    }
  }

  case class Inst(dut:SpikesTable){
    val spikesMonitor = SpikesRspMonitor(dut.io.queryRsp, dut.clockDomain)
    val cmdDriver = SpikeQueryCmdDriver(dut.io.queryCmd, dut.clockDomain)
    val bmbDriver = BmbDriver(dut.io.bus, dut.clockDomain)
    val spikesTableSim = new SpikesTableSim(spikesTableSize)
    bmbDriver.ctrl.cmd.context #= 0
  }

  test("bmb access test"){
    complied.foreach{
      _.doSim{ dut =>
        dut.clockDomain.forkStimulus(2)
        SimTimeout(100000)
        val bmbDriver = BmbDriver(dut.io.bus, dut.clockDomain)
        dut.io.queryCmd.valid #= false
        dut.io.queryRsp.ready #= false
        dut.io.bus.cmd.context #= 0

        val spikesTableSim = new SpikesTableSim(spikesTableSize)
        val initialSpikes = spikesTableSim.busFormatSpikes
        busWrite(bmbDriver, initialSpikes)
        busRead(bmbDriver, initialSpikes)
        // update test
        // first send update cmd
        busUpdate(bmbDriver)
        // then readout the updated spikes for assertion
        spikesTableSim.update()
        busRead(bmbDriver, spikesTableSim.busFormatSpikes)
      }
    }
  }

  test("bmb mask test"){
    complied.foreach{
      _.doSim{ dut =>
        dut.clockDomain.forkStimulus(2)
        SimTimeout(100000)
        dut.io.queryCmd.valid #= false
        dut.io.queryRsp.ready #= false

        val d1, d0:BigInt = Random.nextInt() & (0x1<<spikesLength)-1
        val bmbDriver = BmbDriver(dut.io.bus, dut.clockDomain)
        val address = Random.nextInt(maxAddress)
        val cmd = dut.io.bus.cmd
        cmd.context #= 0

        cmd.valid #= true
        cmd.data #= (d1<<spikesLength) | d0
        cmd.address #= address
        cmd.mask #= "0011".asBin
        cmd.opcode #= 1
        dut.clockDomain.waitSamplingWhere(
          cmd.ready.toBoolean
        )
        cmd.valid #= false
        dut.clockDomain.waitRisingEdge()
        assert((bmbDriver.read(address)&0xFFFF)==d0)

        cmd.valid #= true
        cmd.data #= (d1<<spikesLength)
        cmd.address #= address
        cmd.mask #= "1100".asBin
        cmd.opcode #= 1
        dut.clockDomain.waitSamplingWhere(
          cmd.ready.toBoolean
        )
        cmd.valid #= false
        dut.clockDomain.waitRisingEdge()
        assert(bmbDriver.read(address)==((d1<<spikesLength) | d0))
      }
    }
  }

  test("spike query test"){
    complied.zip(Seq(false, true))
      .foreach{ case (c, isPost) =>
        c.doSim { dut =>
          dut.clockDomain.forkStimulus(2)
          SimTimeout(100000)
          val inst = Inst(dut)
          import inst._

          busWrite(bmbDriver, spikesTableSim.busFormatSpikes)
          val preSpikes = genRandomSpikes()
          spikeQuery(cmdDriver, preSpikes)
          if(!isPost){
            spikesTableSim.spikesInsert(preSpikes)
          }

          val targetSpikes = spikesTableSim.filter(preSpikes)
          spikesMonitor.addCallBacks(targetSpikes, preSpikes)
          spikesMonitor.waitComplete()
          busRead(bmbDriver, spikesTableSim.busFormatSpikes)
        }
      }
  }

  test("post spike insert test"){
    postSpikeTable.doSim(1497358744){dut=>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val inst = Inst(dut)
      import inst._

      spikesTableSim.update() // make sure post-spikes lsb is 0
      busWrite(bmbDriver, spikesTableSim.busFormatSpikes)

      val postSpikes = genRandomSpikes()
      postSpikeInsert(bmbDriver, postSpikes)
      spikesTableSim.spikesInsert(postSpikes, isPost = true)

      val targetSpikes = spikesTableSim.filter(postSpikes)
      spikeQuery(cmdDriver, postSpikes)
      spikesMonitor.addCallBacks(targetSpikes, postSpikes)
      spikesMonitor.waitComplete()
      busRead(bmbDriver, spikesTableSim.busFormatSpikes)
    }
  }
}

class StdpEventGenTest extends AnyFunSuite {
  import StdpTest._

  val nTestCase = 10000
  val spikeMask = (0x1<<spikesLength)-1
  val postSpikeMask = spikeMask - 1
  val deltaTUpBound = spikesLength

  case class SourceSpikes(preSpike:Int,
                          postSpike:Int,
                          virtualSpike:Boolean)

  class StdpEventSim(val isLtp: Boolean,
                     val isLtd: Boolean,
                     val ltdDeltaT:Int,
                     val ltpDeltaT:Int) {

    assert(ltdDeltaT+ltpDeltaT<spikesLength)

    def genSourceSpikes():SourceSpikes = {
      //            ltpDeltaT      ltdDeltaT
      //              |---|-shift N-|-----|
      // pre    * * * 1 0 0 0 0 0 0 0 0 0 *
      // post   * * * 0 0 1 * * * * 1 0 0 0
      // preM   1 1 1 1 0 0 0 0 0 0 0 0 0 0
      // postM  1 1 1 0 0 1 1 1 1 1 1 0 0 0
      val ltdOnly = !isLtp && isLtd
      val virtualSpike = isLtp && !isLtd
      val prePreSpike = if(virtualSpike){
        1<<(spikesLength-1)
      }else if(ltdOnly){
        1<<spikesLength
      }else{
        val shiftN = Random.nextInt(spikesLength-ltdDeltaT-ltpDeltaT)
        1<<(ltpDeltaT+shiftN+ltdDeltaT)
      }
      val postSpike0 = 1<<ltdDeltaT
      val postSpike1 = prePreSpike>>ltpDeltaT

      val preMask =  ((prePreSpike-1) ^ spikeMask)
      val postMask = (((preMask<<1)&spikeMask) | ((postSpike1<<1)-postSpike0)) & postSpikeMask
      val preSpike = (Random.nextInt() | prePreSpike) & (preMask + (!virtualSpike).toInt)
      val postSpike = (Random.nextInt() | postSpike1 | postSpike0) & postMask

      SourceSpikes(preSpike, postSpike , virtualSpike)
    }
  }

  object StdpEventSim{

    def virtualEvent():Seq[StdpEventSim] = {
      (0 until spikesLength-1).map{ ltpDeltaT =>
        new StdpEventSim(isLtp = true, isLtd = false, ltdDeltaT = 0, ltpDeltaT)
      }
    }
    def ltdEvent():Seq[StdpEventSim] = {
      (1 until spikesLength).map{ ltdDeltaT =>
        new StdpEventSim(isLtp = false, isLtd = true, ltdDeltaT, ltpDeltaT=0)
      }
    }
    def stdpEvent(n:Int):Seq[StdpEventSim] = {
      val ltdDeltaTs = Seq.fill(n)(Random.nextInt(spikesLength-1)+1)
      val ltpDeltaTs = ltdDeltaTs.map(ltdT=>Random.nextInt(spikesLength-ltdT))
      ltdDeltaTs.zip(ltpDeltaTs).map{case(ltdDeltaT, ltpDeltaT) =>
        new StdpEventSim(isLtp = true, isLtd = true, ltdDeltaT, ltpDeltaT)
      }
    }
  }

  test("stdp event generate test"){
    SimConfig.withWave.compile(new StdpEventGen).doSim{ dut=>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(1000000)
      val (_, preSpikeQueue) = StreamDriver.queue(dut.io.preSpikes, dut.clockDomain)
      val (_, postSpikeQueue) = StreamDriver.queue(dut.io.postSpikes, dut.clockDomain)
      val virtualEvent = StdpEventSim.virtualEvent()
      val ltdOnlyEvent = StdpEventSim.ltdEvent()
      val stdpEvent = StdpEventSim.stdpEvent(nTestCase)
      val events = virtualEvent ++ ltdOnlyEvent ++ stdpEvent
      for(e <- events){
        val s = e.genSourceSpikes()
        preSpikeQueue.enqueue{ cmd=>
          cmd.virtualSpike #= s.virtualSpike
          cmd.spikes #= s.preSpike
        }
        postSpikeQueue.enqueue{ cmd=>
          cmd.spikes #= s.postSpike
        }
      }
      dut.io.stdpEvent.ready #= true
      for(e <- events){
        dut.clockDomain.waitRisingEdgeWhere(dut.io.stdpEvent.valid.toBoolean)
        assert(dut.io.stdpEvent.isLtp.toBoolean==e.isLtp)
        assert(dut.io.stdpEvent.isLtd.toBoolean==e.isLtd)
        assert(dut.io.stdpEvent.ltdDeltaT.toInt==e.ltdDeltaT)
        assert(dut.io.stdpEvent.ltpDeltaT.toInt==e.ltpDeltaT)
      }
    }
  }
}