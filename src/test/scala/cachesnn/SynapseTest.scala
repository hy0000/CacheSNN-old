package cachesnn

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.bus.bmb.sim._

class SynapseTest extends AnyFunSuite{
  val compiled = SimConfig
    .withWave
    .compile(new Synapse)

  test("bmb test"){
    compiled.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      //dut.io.out0.rsp.valid #= false
      //dut.io.out0.rsp.source #= 1
      //dut.io.out0.rsp.opcode #= 0

      //dut.io.out0.cmd.ready #= true
      dut.io.in.cmd.source #= 1
      dut.io.in.cmd.address #= 0
      dut.io.in.cmd.opcode #= 0
      dut.io.in.cmd.length #= 64
      dut.io.in.cmd.data #= 0xF
      dut.io.in.cmd.mask #= 0xF
      dut.io.in.cmd.context #= 0
      dut.io.in.cmd.valid #= true
      dut.io.in.cmd.last #= true
      dut.io.in.rsp.ready #= true

      dut.clockDomain.waitRisingEdgeWhere(
        dut.io.in.cmd.ready.toBoolean
      )
      dut.io.in.cmd.valid #= false
      //dut.clockDomain.waitRisingEdgeWhere(
      //  dut.io.out0.rsp.valid.toBoolean
      //)
      //dut.io.out0.rsp.valid #= true
      dut.clockDomain.waitRisingEdge(100)
    }
  }
}
