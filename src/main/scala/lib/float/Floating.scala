package lib.float

import spinal.core._
import spinal.lib._
import spinal.lib.experimental.math.{Floating => spinalFloating}

class Floating(exponentSize: Int, mantissaSize: Int)
  extends spinalFloating(exponentSize, mantissaSize) {
}

class FpCmd(floatType: Floating) extends Bundle {
  val src1, src2 = floatType
}

case class FpFlags() extends Bundle{
  val NX,  UF,  OF,  DZ,  NV = Bool()
}

case class FpResult(floatType: Floating) extends Bundle {
  val value = floatType
  val flag = FpFlags()
}

case class FpAddCmd(floatType: Floating) extends FpCmd(floatType){
  val sub = Bool()
}

class FpAdd(floatType: Floating) extends Component{
  val io = new Bundle {
    val cmd = slave Stream(FpAddCmd(floatType))
    val res = master Stream(FpResult(floatType))
  }

  case class PreShifter() extends Bundle {
    val absRs1Bigger = Bool()
    val rs1ExponentBigger = Bool()
  }

}