package cachesnn

import spinal.core._
import spinal.lib._
import cachesnn.Synapse.bmbParameter
import lib.noc.NocLocalIf
import spinal.core.fiber.Handle
import spinal.lib.bus.bmb._
import spinal.lib.bus.misc.SizeMapping
import lib.saxon._

object Synapse {
  import cachesnn.Stdp._

  val channels = 4
  val bmbParameter = BmbParameter(
    addressWidth = 10,
    dataWidth = 32,
    sourceWidth = 2,
    contextWidth = 0,
    lengthWidth = 8,
    alignment = BmbParameter.BurstAlignement.WORD
  )
}

class Synapse extends Component {
  val io = new Bundle {
    //val noc = master(NocLocalIf())
    val in = slave(Bmb(bmbParameter))
    //val s = master(Bmb(bmbParameter))
  }
  val m = Handle(io.in)
  //val s = Handle(io.s)
  val acc = Handle(BmbAccessParameter(10, 32))
  acc.sources.put(1, BmbSourceParameter(2, 8))
  implicit val interconnect = BmbInterconnectGenerator()
  interconnect.addMaster(
    accessRequirements = acc,
    bus = m
  )
  //interconnect.addSlave(
  //  accessSource       = Handle[BmbAccessCapabilities],
  //  accessCapabilities = Handle(BmbOnChipRam.busCapabilities(1024, 32)),
  //  accessRequirements = Handle[BmbAccessParameter],
  //  bus = s,
  //  mapping = Handle(SizeMapping(0, BigInt(1) << log2Up(1024)))
  //)
  val ramA = BmbOnChipRamGenerator(0)
  ramA.size.load(BigInt(1024))
  //interconnect.addSlave(
  //  accessCapabilities = Handle(BmbAccessCapabilities(10, 32, lengthWidthMax = 8)),
  //  accessRequirements = Handle[BmbAccessParameter],
  //  bus = s0,
  //  mapping = Handle(SizeMapping(0, 4*1024))
  //)
  //interconnect.addSlave(
  //  accessCapabilities = Handle(BmbAccessCapabilities(10, 32, lengthWidthMax = 8)),
  //  accessRequirements = Handle[BmbAccessParameter],
  //  bus = s1,
  //  mapping = Handle(SizeMapping(4*1024, 8*1024))
  //)
  //interconnect.addConnection(m, s)
  interconnect.addConnection(m, ramA.ctrl)
}

object SynapseVerilog extends App {
  SpinalVerilog(new Synapse)
}

