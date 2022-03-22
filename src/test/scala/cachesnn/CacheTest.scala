package cachesnn

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4WriteOnly
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.misc.SizeMapping
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

  test("uram bank wave"){
    SimConfig.withWave.compile(new UramBankInst).doSim{dut=>
      dut.clockDomain.forkStimulus(2)
      dut.io.r.rsp.ready #= true
      for(i <- 0 until 1024){
        dut.io.w.address #= i
        dut.io.w.valid #= true
        dut.io.w.data #= i
        dut.io.w.mask #= 0xFF
        dut.clockDomain.waitRisingEdge()
        dut.io.r.cmd.valid #= true
        dut.io.r.cmd.payload #= i
      }
    }
  }
}

class CacheBanksTest extends AnyFunSuite {

  class AxiCacheBanks extends Component {
    val inst = new CacheBanks(64)
    val c = inst.getAxi4Config
    val axiR = inst.io.read.map(r => slave(r.toAxi4ReadOnlySlave(c, inst.UramLatency)))
    val axiW = inst.io.write.map(w => slave(w.toAxi4WriteOnly(c)))
  }

  val offsetWidth = 8+3 // offset word width + axi word width
  val burstLen = 255

  test("no conflict write-read"){
    SimConfig.withWave.compile(new AxiCacheBanks).doSim{dut=>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      for(axi <- dut.axiW){
        axi.aw.valid #= false
        axi.aw.burst #= 1
        axi.aw.size #= 3
        axi.w.valid #= false
        axi.b.ready #= true
      }
      for(axi <- dut.axiR){
        axi.ar.valid #= false
        axi.ar.burst #= 1
        axi.ar.size #= 3
        axi.r.ready #= true
      }

      (0 to 1).map{ portId =>
        fork{
          for(i <- portId to 21 by 2){
            val wp = dut.axiW(portId)
            wp.writeCmd.addr #= i << offsetWidth
            wp.writeCmd.valid #= true
            wp.writeCmd.len #= burstLen
            wp.writeData.strb #= 0xFF
            wp.writeData.valid #= true
            for(j <- 0 to burstLen){
              wp.writeData.data #= (i<<offsetWidth) + j
              dut.clockDomain.waitSamplingWhere(
                wp.writeData.ready.toBoolean
              )
            }
            wp.writeCmd.valid #= false
            wp.writeData.valid #= false
          }
        }
      }
      // check is axi b raise at the same time to confirm no conflict
      for(_ <- 0 to 21 by 2){
        dut.clockDomain.waitRisingEdgeWhere(
          dut.axiW(0).b.valid.toBoolean
        )
        assert(dut.axiW(1).b.valid.toBoolean)
      }

      // read test
      (0 to 1).map{ portId =>
        fork {
          for (i <- portId to 21 by 2) {
            val rp = dut.axiR(portId)
            rp.readCmd.addr #= i << offsetWidth
            rp.readCmd.valid #= true
            rp.readCmd.len #= burstLen
            dut.clockDomain.waitSamplingWhere(
              rp.readCmd.ready.toBoolean
            )
            rp.readCmd.valid #= false

            for(j <- 0 to burstLen){
              dut.clockDomain.waitRisingEdgeWhere(
                rp.readRsp.valid.toBoolean
              )
              assert(rp.readRsp.data.toBigInt == (i<<offsetWidth) + j)
              assert(rp.readRsp.last.toBoolean == (j==burstLen))
            }
          }
        }
      }.foreach(_.join())
    }
  }
}