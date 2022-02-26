package lib.float

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class FloatingAddTest extends AnyFunSuite{

  val F16 = FloatType(5, 10)
  val compiled = SimConfig
    .withWave
    .compile(new FloatAdd(F16))

  test("test"){
    compiled.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      dut.io.res.ready #= true
      dut.io.cmd.valid #= true
      dut.io.cmd.sub #= false
      for(i <- 0 to 100){
        //dut.io.cmd.src1.sign #= false
        //dut.io.cmd.src1.exponent #= 15
        //dut.io.cmd.src1.mantissa #= 0
        //dut.io.cmd.src2.sign #= true
        //dut.io.cmd.src2.exponent #= 16
        //dut.io.cmd.src2.mantissa #= 0
        //dut.io.cmd.src1 #= 1
        //dut.io.cmd.src2 #= -1
        dut.clockDomain.waitRisingEdge()
      }
    }
  }

}
