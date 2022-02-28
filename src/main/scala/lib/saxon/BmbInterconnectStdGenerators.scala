package lib.saxon

import spinal.core._
import spinal.core.fiber._
import spinal.lib._
import spinal.lib.bus.amba3.apb.{Apb3, Apb3CC, Apb3Config, Apb3SlaveFactory}
import spinal.lib.bus.bmb.{Bmb, BmbAccessCapabilities, BmbAccessParameter, BmbArbiter, BmbEg4S20Bram32K, BmbExclusiveMonitor, BmbIce40Spram, BmbImplicitPeripheralDecoder, BmbInterconnectGenerator, BmbInvalidateMonitor, BmbInvalidationParameter, BmbOnChipRam, BmbOnChipRamMultiPort, BmbParameter, BmbToApb3Bridge}
import spinal.lib.bus.misc.{AddressMapping, DefaultMapping, SizeMapping}
import spinal.lib.generator.{Dependable, Export, Generator, MemoryConnection}
import spinal.lib.memory.sdram.SdramLayout
import spinal.lib.memory.sdram.sdr._
import spinal.lib.memory.sdram.xdr._
import spinal.lib.memory.sdram.xdr.phy.{Ecp5Sdrx2Phy, RtlPhy, SdrInferedPhy, XilinxS7Phy}

import scala.collection.mutable.ArrayBuffer

case class BmbOnChipRamGenerator(val address: Handle[BigInt] = Unset)
                                (implicit interconnect: BmbInterconnectGenerator) extends Area {
  val size      = Handle[BigInt]
  var hexOffset = BigInt(0)
  val source = Handle[BmbAccessCapabilities]
  val requirements = Handle[BmbAccessParameter]
  val ctrl = Handle(logic.io.bus)

  interconnect.addSlave(
    accessSource       = source,
    accessCapabilities = Handle(BmbOnChipRam.busCapabilities(size, source.dataWidth)),
    accessRequirements = requirements,
    bus = ctrl,
    mapping = Handle(SizeMapping(address, BigInt(1) << log2Up(size)))
  )


  val logic = Handle(BmbOnChipRam(
    p = requirements.toBmbParameter(),
    size = size,
    hexOffset = address.get + hexOffset
  ))

  export[BigInt](size, size.toInt)
}


