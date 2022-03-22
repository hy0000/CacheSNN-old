package cachesnn

import spinal.core._
import cachesnn.AerBus._
import lib.float.BF16
import spinal.lib.bus.bmb._

object AerBus {
  val timeStampWidth = 16
  val weightWidth = 16
  val stdpTimeWindowWidth = 16
}

object EventType extends SpinalEnum {
  val preSpikeEvent, postSpikeEvent, currentEvent = newElement()
}

class AerBus(nidWidth:Int, offsetWidth: Int, lengthWidth:Int) extends Bundle {
  val nid = UInt(nidWidth bits)
  val offset = UInt(offsetWidth bits)
  val length = UInt(lengthWidth bits)
}