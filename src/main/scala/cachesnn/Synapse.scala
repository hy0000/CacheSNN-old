package cachesnn

import spinal.core._
import spinal.lib._
import cachesnn.Synapse.{bmbParameter, preSpikeTableSize}
import lib.noc.NocLocalIf
import spinal.core.fiber.Handle
import spinal.lib.bus.bmb._
import spinal.lib.bus.misc.SizeMapping
import lib.saxon._

object Synapse {

  val threads = 2
  val postSpikeTableSize = 4 KiB
  val stdpTimeWindowBytes = 2
  val postNeuronPreThread = postSpikeTableSize/stdpTimeWindowBytes/threads
  val cacheSize = 1 MiB
  val sparsity = 4
  val synapseDataByte = 4
  val cacheLineSize = postNeuronPreThread/sparsity*synapseDataByte
  val firingRate = 4
  val preNeuron = cacheSize/cacheLineSize*firingRate
  val preSpikeTableSize = preNeuron*stdpTimeWindowBytes
  val channels = 4
  val bmbParameter = BmbParameter(
    addressWidth = 12,
    dataWidth = 32,
    sourceWidth = 2,
    contextWidth = 1,
    lengthWidth = 8,
    alignment = BmbParameter.BurstAlignement.WORD
  )

  def extractSrcContext(context: Bits):Bits = {
    val h = context.getWidth - 1
    val l = context.getWidth - bmbParameter.access.contextWidth
    context(h downto l)
  }
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
  println(preSpikeTableSize)
}

