package cachesnn

import spinal.core._
import spinal.lib._
import cachesnn.AerBus._
import spinal.lib.bus.amba4.axi._
/*
case class CacheOut() extends Bundle {
  val preNeuronId = UInt(neuronIdWidth bits)
  val stdpParam = ???
  val ltpOnly = Bool()
}

class Cache(nParallel: Int) extends Component {
  val io = new Bundle {
    val dataBus = ???
    val spike = slave Stream new AerBus
    val ltpEvent = slave Stream UInt(neuronIdWidth bits)
    val cacheOut = master Stream CacheOut()
  }
}
*/
