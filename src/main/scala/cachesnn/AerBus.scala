package cachesnn

import spinal.core._
import cachesnn.AerConfig._
import lib.float.BF16

object AerConfig {
  val NEURON_ID_WIDTH = 16
  val TIMESTAMP_WIDTH = 16
}

object EventType extends SpinalEnum {
  val preSpikeEvent, postSpikeEvent, currentEvent = newElement()
}

class AerBase extends Bundle {
  val neuronId = UInt(NEURON_ID_WIDTH bits)
  val timestamp = UInt(TIMESTAMP_WIDTH bits)
}

class AerBus extends AerBase {
  val current = BF16
  val eventType = EventType
}

class StdpPipeBase extends Bundle {
  val weight = BF16
  val postNeuronId = UInt(NEURON_ID_WIDTH bits)
  val offset = UInt(NEURON_ID_WIDTH bits)
}

class SpikeHis extends StdpPipeBase {
  val ltpEvent = UInt(NEURON_ID_WIDTH bits)
}