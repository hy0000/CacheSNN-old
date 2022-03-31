package cachesnn

import cachesnn.Synapse.{cacheAxi4Config, spikeTableBmbParameter}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.bmb.Bmb
import spinal.lib.bus.bmb.sim.BmbMemoryAgent
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.sim.{StreamDriver, StreamReadyRandomizer}

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