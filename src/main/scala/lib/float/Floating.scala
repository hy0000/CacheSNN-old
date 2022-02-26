package lib.float

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.annotation.tailrec
import scala.math.{log, pow}

case class FloatDecoded() extends Bundle{
  val isNan = Bool()
  val isNormal = Bool()
  val isSubnormal = Bool()
  val isZero = Bool()
  val isInfinity = Bool()
  val isQuiet = Bool()
}

object Floating{
  val ZERO = 0
  val INFINITY = 1
  val NAN = 2
  val NAN_CANONICAL_BIT = 2
}

object FloatRoundMode extends SpinalEnum(){
  val RNE, RTZ, RDN, RUP, RMM = newElement()
  defaultEncoding = SpinalEnumEncoding("opt")(
    RNE -> 0,
    RTZ -> 1,
    RDN -> 2,
    RUP -> 3,
    RMM -> 4
  )
}

case class FloatType(exponentSize: Int, mantissaSize: Int)

case class Floating(floatType: FloatType) extends Bundle {
  val mantissa = UInt(floatType.mantissaSize bits)
  val exponent = UInt(floatType.exponentSize bits)
  val sign = Bool()

  def bias = (exponent.maxValue>>1).toInt

  def expZero     = exponent === 0
  def expOne      = exponent === exponent.maxValue
  def manZero     = mantissa === 0
  def isZero      =  expZero &&  manZero
  def isSubnormal =  expZero && !manZero
  def isNormal    = !expOne  && !expZero
  def isInfinity  =  expOne  &&  manZero
  def isNan       =  expOne  && !manZero
  def isQuiet     =  mantissa.msb

  def setZero()      =  { exponent := 0; mantissa := 0 }
  def setInfinity()  =  { exponent := exponent.maxValue; mantissa := 0 }
  def setNanQuiet()  =  { exponent := 0; mantissa.msb := True }
}

class FloatOpCmd(floatType: FloatType) extends Bundle {
  val src1, src2 = Floating(floatType)
}

class FloatAddCmd(floatType: FloatType) extends FloatOpCmd(floatType){
  val sub = Bool()
}

class FloatAdd(floatType: FloatType) extends Component{
  val io = new Bundle {
    val cmd = slave Stream new FloatAddCmd(floatType)
    val res = master Stream Floating(floatType)
  }

  // carry bit + mantissa + guard bit + round bit
  val extendedMantissaSize = floatType.mantissaSize + 3

  class PreShifter() extends FloatAddCmd(floatType) {
    val srcZero = Bool()
    val exp2sub1 = UInt(floatType.exponentSize + 1 bits)
    val absSrc1Bigger = Bool()
    val src1ExponentBigger = Bool()
  }

  val preShifter = io.cmd.translateWith {
    val exp2sub1 =  io.cmd.src2.exponent -^ io.cmd.src1.exponent
    val src1ExponentBigger = (exp2sub1.msb || io.cmd.src2.isZero) && !io.cmd.src1.isZero
    val src1ExponentEqual = io.cmd.src1.exponent === io.cmd.src2.exponent
    val src1MantissaBigger = io.cmd.src1.mantissa > io.cmd.src2.mantissa
    val absSrc1Bigger = ((src1ExponentBigger || src1ExponentEqual && src1MantissaBigger) && !io.cmd.src1.isZero || io.cmd.src1.isInfinity) && !io.cmd.src2.isInfinity

    val ret = new PreShifter()
    ret.assignSomeByName(io.cmd.payload)
    ret.srcZero := io.cmd.src1.isZero || io.cmd.src2.isZero
    ret.exp2sub1 := exp2sub1
    ret.absSrc1Bigger := absSrc1Bigger
    ret.src1ExponentBigger := src1ExponentBigger
    ret
  }.stage()

  trait ShifterOutputTrait extends Bundle {
    // x is the bigger src, y is the smaller
    val xSign, ySign = Bool()
    val xMantissa, yMantissa = UInt(extendedMantissaSize bits)
    val xyExponent = UInt(floatType.exponentSize bits)
    val xySign = Bool()
    val sticky = Bool()
  }

  class ShifterPipe extends PreShifter with ShifterOutputTrait {
    val exp2sub1Abs = UInt(floatType.exponentSize + 1 bits)
  }

  class ShifterOutput extends FloatAddCmd(floatType) with ShifterOutputTrait

  val shifter = new Area {
    val shifterInput = preShifter.translateWith{
      val src1Bigger = preShifter.absSrc1Bigger
      val xySign = src1Bigger ? preShifter.src1.sign | preShifter.src2.sign
      val ret = new ShifterPipe
      ret.assignSomeByName(preShifter.payload)
      ret.exp2sub1Abs := preShifter.exp2sub1.asSInt.abs
      ret.xySign := xySign
      ret.xSign  := xySign ^ (src1Bigger ? preShifter.src1.sign | preShifter.src2.sign)
      ret.ySign  := xySign ^ (src1Bigger ? preShifter.src2.sign | preShifter.src1.sign)
      ret.xMantissa := U"1" @@ (src1Bigger ? preShifter.src1.mantissa | preShifter.src2.mantissa) @@ U(0, 2 bits)
      ret.yMantissa := U"1" @@ (src1Bigger ? preShifter.src2.mantissa | preShifter.src1.mantissa) @@ U(0, 2 bits)
      ret.xyExponent := src1Bigger ? preShifter.src1.exponent | preShifter.src2.exponent
      ret.sticky := False
      ret
    }

    @tailrec
    def pipeShift(sp: Stream[ShifterPipe], shiftId: Int = 0, offset: Int = 1): Stream[ShifterPipe] ={
      if(offset <= sp.yMantissa.getBitsWidth) {
        val shiftedEm = sp.translateWith{
          val shiftBy = sp.exp2sub1(shiftId)
          val ret = new ShifterPipe
          ret.assignSomeByName(sp.payload)
          ret.sticky.removeAssignments()
          ret.sticky  := sp.sticky | (sp.yMantissa(0, offset bits) =/= 0)
          when(shiftBy){
            ret.yMantissa := sp.yMantissa |>> offset
          }
          ret
        }
        val piped = if(shiftId%2 == 1) {
          shiftedEm.stage()
        }else{
          shiftedEm
        }
        pipeShift(piped, shiftId+1, offset*2)
      }else{
        sp
      }
    }

    val shifted = pipeShift(shifterInput)
    val shiftOverflow = shifted.exp2sub1Abs > extendedMantissaSize
    val passThrough = shiftOverflow || shifted.srcZero
    val output = shifted.translateWith{
      val ret = new ShifterOutput
      ret.assignAllByName(shifted.payload)
      when(passThrough)   { ret.yMantissa := 0 }
      when(shiftOverflow) { ret.sticky := True }
      when(!shifted.src1.isNormal || !shifted.src2.isNormal){ ret.sticky := False }
      ret
    }.stage()
  }

  class MathOutput() extends ShifterOutput {
    val xyMantissa = UInt(extendedMantissaSize + 1 bits)
  }

  val mathOutput = shifter.output.translateWith{
    val ret = new MathOutput()
    ret.assignSomeByName(shifter.output.payload)
    import shifter.output.payload._

    val xSigned = xMantissa.twoComplement(xSign)
    val ySigned = ((ySign ## Mux(ySign, ~yMantissa, yMantissa)).asUInt + (ySign && !sticky).asUInt).asSInt
    ret.xyMantissa := U(xSigned +^ ySigned).trim(1 bits)
    ret
  }.stage()

  case class OhOutput() extends MathOutput {
    val shift = UInt(log2Up(xyMantissa.getWidth) bits)
  }

  val ohOutput = mathOutput.translateWith{
    val ret = OhOutput()
    ret.assignSomeByName(mathOutput.payload)
    val shiftOh = OHMasking.first(mathOutput.xyMantissa.asBools.reverse)
    ret.shift := OHToUInt(shiftOh)
    ret
  }.stage()

  class NormOutput extends FloatAddCmd(floatType) {
    val mantissa = UInt(extendedMantissaSize+1 bits)
    val exponent = UInt(floatType.exponentSize+1 bits)
    val infinityNan, forceNan, forceZero, forceInfinity = Bool()
    val xySign, roundingScrap = Bool()
    val xyMantissaZero = Bool()
  }

  val normOutput = ohOutput.translateWith {
    val ret = new NormOutput
    ret.assignSomeByName(ohOutput.payload)
    import ohOutput.payload._

    ret.mantissa := xyMantissa |<< shift
    ret.exponent := xyExponent -^  shift + 1
    ret.forceInfinity := (src1.isInfinity || src2.isInfinity)
    ret.forceZero := xyMantissa === 0 || (src1.isZero && src2.isZero)
    ret.infinityNan :=  (src1.isInfinity && src2.isInfinity && (src1.sign ^ src2.sign))
    ret.forceNan := src1.isNan || src2.isNan || ret.infinityNan
    ret.xyMantissaZero := xyMantissa === 0
    ret
  }.stage()

  io.res <-< normOutput.translateWith {
    val ret = Floating(floatType)
    import normOutput.payload._
    ret.mantissa := (mantissa>>2).resized
    ret.sign := xySign
    ret.exponent := normOutput.exponent.resized
    when(forceNan) {
      ret.setNanQuiet()
    } elsewhen (forceInfinity) {
      ret.setInfinity()
    } elsewhen (forceZero) {
      ret.setZero()
      when(xyMantissaZero || src1.isZero && src2.isZero) {
        ret.sign := src1.sign && src2.sign
      }
    }
    ret
  }
}

object BF16 {
  def apply() = FloatType(8, 7)
}

object FloatAddVerilog extends App {
  SpinalVerilog(new FloatAdd(BF16()))
}