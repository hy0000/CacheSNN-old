package cachesnn

import spinal.core._
import spinal.lib._
import cachesnn.AerBus._
import cachesnn.Synapse.bmbParameter
import lib.noc
import spinal.lib.bus.amba4.axi._
import lib.noc.NocLocalIf
import spinal.core.fiber.Handle
import spinal.lib.bus.bmb._
import spinal.lib.bus.misc.SizeMapping

object Synapse {
  import cachesnn.Stdp._

  val channels = 4
  val bmbParameter = BmbParameter(
    addressWidth = log2Up(spikeBufferSize*2+stdpFpuMemSize*channels),
    dataWidth = 32,
    sourceWidth = 2,
    contextWidth = 0,
    lengthWidth = 8,
    alignment = BmbParameter.BurstAlignement.BYTE
  )
}

class Synapse extends Component {
  val io = new Bundle {
    //val noc = master(NocLocalIf())
    val in = slave(Bmb(bmbParameter))
    val out0 = master(Bmb(bmbParameter))
    //val out1 = master(Bmb(bmbParameter))
  }

  val m = Handle(io.in)
  val s0 = Handle(io.out0)
  //val s1 = Handle(io.out1)
  val acc = Handle(BmbAccessParameter(10, 32))
  acc.sources.put(1, BmbSourceParameter(2, 8))
  val interconnect = BmbInterconnectGenerator()
  interconnect.addMaster(
    accessRequirements = acc,
    bus = m
  )
  interconnect.addSlave(
    accessCapabilities = Handle(BmbAccessCapabilities(10, 32, lengthWidthMax = 8)),
    accessRequirements = Handle[BmbAccessParameter],
    bus = s0,
    mapping = Handle(SizeMapping(0, 4*1024))
  )
  //interconnect.addSlave(
  //  accessCapabilities = Handle(BmbAccessCapabilities(10, 32, lengthWidthMax = 8)),
  //  accessRequirements = Handle[BmbAccessParameter],
  //  bus = s1,
  //  mapping = Handle(SizeMapping(4*1024, 8*1024))
  //)
  interconnect.addConnection(m, s0)
  //interconnect.addConnection(m, s1)
}

object SynapseVerilog extends App {
  SpinalVerilog(new Synapse)
}

