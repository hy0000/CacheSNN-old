package cachesnn

import spinal.core._
import spinal.lib._
import cachesnn.AerConfig._
import spinal.lib.bus.amba4.axi._

case class CacheOut() extends Bundle {
  val preNeuronId = UInt(NEURON_ID_WIDTH bits)
  val stdpParam = Vec(new StdpPipeBase, SynapseConfig.nParallel)
  val ltpOnly = Bool()
}

class Cache(nParallel: Int) extends Component {
  val io = new Bundle {
    val dataBus = master(Axi4(SynapseConfig.axiConfig))
    val spike = slave Stream new AerBus
    val ltpEvent = slave Stream UInt(NEURON_ID_WIDTH bits)
    val cacheOut = master Stream CacheOut()
  }
}
