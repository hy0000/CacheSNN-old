package cachesnn

import spinal.core._
import spinal.lib._
import cachesnn.AerConfig._
import spinal.lib.bus.amba4.axi._
import lib.float.BF16

object SynapseConfig {
  val nParallel = 4
  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth    = 32
  )
}

case class SynapticEvent() extends AerBase {
  val weight = BF16
}

class CurrentAcc(nParallel: Int) extends Component {
  val io = new Bundle {
    val current = master Stream new AerBus
    val synapticEvent = Vec(slave Stream SynapticEvent(), nParallel)
    val readOut = in Bool()
  }

  io.current.assignFromBits(0)
  io.synapticEvent.assignFromBits(0)
}

class Synapse extends Component {
  val io = new Bundle {
    val spike = slave Stream new AerBus
    val current = master Stream new AerBus
    val dataBus = master(Axi4(SynapseConfig.axiConfig))
  }
}


