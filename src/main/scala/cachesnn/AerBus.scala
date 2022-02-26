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

class AerBase extends Bundle {
  //val neuronId = UInt(neuronIdWidth bits)
  val timestamp = UInt(timeStampWidth bits)
}

class AerBus extends AerBase {
  val current = BF16
  val eventType = EventType
}