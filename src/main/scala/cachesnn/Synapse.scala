package cachesnn

import cachesnn.AerBus.stdpTimeWindowWidth
import cachesnn.Cache.{ssnWidth, tagRamAddressWidth, tagTimeStampWidth, tagWidth}
import spinal.core._
import spinal.lib._
import cachesnn.Synapse._
import lib.noc.NocLocalIf
import spinal.core.fiber.Handle
import spinal.lib.bus.bmb._
import spinal.lib.bus.misc.SizeMapping
import lib.saxon._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm._

object Synapse {

  val threads = 2
  //val postSpikeTableSize = 4 KiB
  //val stdpTimeWindowBytes = 2
  //val postNeuronPreThread = postSpikeTableSize/stdpTimeWindowBytes/threads
  //val cacheSize = 1 MiB
  //val sparsity = 4
  //val synapseDataByte = 4
  //val cacheLineSize = postNeuronPreThread/sparsity*synapseDataByte
  //val firingRate = 4
  //val preNeuron = cacheSize/cacheLineSize*firingRate
  //val preSpikeTableSize = preNeuron*stdpTimeWindowBytes
  //val channels = 4
  val neuronSize = 2048
  val nidWidth = log2Up(neuronSize)
  val weightWidth = 16
  val busDataWidth = 64
  val nCacheBank = 8
  val cacheBankSize = 32 KiB
  val cacheLenMax = Cache.cacheLineSize*8/busDataWidth
  val cacheLenWidth = log2Up(cacheLenMax)
  val spikeTableSize = neuronSize*2
  val postParamWordSize = 16 Byte // 8 parameter maybe enough
  val postParamSize = postParamWordSize*neuronSize
  val nPostParamRam = 4
  val cacheAxi4Config = Axi4Config(
    addressWidth = 18, // 17 for 128 KB cache, 1 for bypass
    dataWidth = busDataWidth,
    idWidth = 1,
    useRegion = false,
    useCache = false,
    useProt = false,
    useQos = false,
    useLock = false
  )
  val tagRamBmbParameter = BmbParameter(
    addressWidth = log2Up(BmbAddress.size),
    dataWidth = 128,
    sourceWidth = 0,
    contextWidth = 0,
    lengthWidth = log2Up(Cache.tagStep),
    alignment = BmbParameter.BurstAlignement.WORD
  )
  val spikeTableBmbParameter = BmbParameter(
    addressWidth = log2Up(BmbAddress.size),
    dataWidth = 64,
    sourceWidth = 0,
    contextWidth = 2,
    lengthWidth = cacheLenWidth,
    alignment = BmbParameter.BurstAlignement.WORD
  )
  val postParamBmbParameter = BmbParameter(
    addressWidth = log2Up(postParamSize/nPostParamRam),
    dataWidth = postParamWordSize.toInt*8,
    sourceWidth = 0,
    contextWidth = 0,
    lengthWidth = cacheLenWidth,
    alignment = BmbParameter.BurstAlignement.WORD
  )
  object SpikeActionBmbContext {
    val INSERT = 1
    val UPDATE = 2
    val WR = 0
  }

  def extractSrcContext(context: Bits):Bits = {
    val h = context.getWidth - 1
    val l = context.getWidth - spikeTableBmbParameter.access.contextWidth
    context(h downto l)
  }

  def cacheAddressToBankSel(address: UInt):UInt = {
    assert(address.getWidth==cacheAxi4Config.addressWidth)
    address.dropLow(log2Up(Cache.cacheLineSize)).takeLow(log2Up(nCacheBank)).asUInt
  }

  object BmbAddress{
    val tagRam = SizeMapping(0, 512 Byte)
    val preSpikeTable = SizeMapping(4 KiB, spikeTableSize)
    val postSpikeTable = SizeMapping(preSpikeTable.end + 1, spikeTableSize)
    val postParam = SizeMapping(postSpikeTable.end + 1, postParamSize)
    def size = preSpikeTable.size + postSpikeTable.size + postParamSize
  }

  object GlobalAddress{
    val cacheBanks = (0 until nCacheBank).map(i => SizeMapping(i*cacheBankSize, cacheBankSize))
    val bmbSpace = SizeMapping(cacheBanks.last.end + 1, BmbAddress.size)
    val cache = SizeMapping(0, cacheBankSize*nCacheBank)

    def printAddressMapping(): Unit ={
      import BmbAddress._
      println(f"cache:          ${cache.base.hexString(20)}-${cache.end.hexString(20)}  ${nCacheBank*cacheBankSize/1024}%3d KB")
      println(f"preSpikeTable:  ${(bmbSpace.base+preSpikeTable.base).hexString(20)}-${(bmbSpace.base+preSpikeTable.end).hexString(20)}  ${spikeTableSize/1024}%3d KB")
      println(f"postSpikeTable: ${(bmbSpace.base+postSpikeTable.base).hexString(20)}-${(bmbSpace.base+postSpikeTable.end).hexString(20)}  ${spikeTableSize/1024}%3d KB")
      println(f"postParam:      ${(bmbSpace.base+postParam.base).hexString(20)}-${(bmbSpace.base+postParam.end).hexString(20)}  ${postParamSize/1024}%3d KB")
    }
  }

  assert(cacheLenWidth<=8) // should smaller than axi burst length
  assert(nidWidth+3<=16)
}

class Spike extends Bundle {
  val nid = UInt(nidWidth bits)
  val virtual = Bool()
  val ssn = UInt(ssnWidth bits)
  val thread = UInt(log2Up(threads) bits)

  def tag:UInt = nid.takeHigh(tagWidth).asUInt
}

class MetaSpike extends Spike {
  val len = UInt(cacheLenWidth bits)
  val lastWordMask = Bits(busDataWidth/16 bits)
  val threadAddressBase = UInt(nidWidth bits)
  val dense = Bool()

  def lastByteMask:Bits = {
    B(lastWordMask.asBools
      .zip(lastWordMask.asBools)
      .map(m =>m._1 ## m._2)
      .reduce(_##_))
  }
}

case class MetaSpikeT() extends MetaSpike {
  val tagTimeStamp = UInt(tagTimeStampWidth bits)
}

class MissSpike extends MetaSpike {
  val cacheAddressBase = cacheAxi4Config.addressType
  val tagState = TagState()
  val cover = Bool()
  val replaceNid = UInt(nidWidth bits)
}

case class ReadySpike() extends MetaSpike {
  val cacheAddressBase = cacheAxi4Config.addressType
}

// pack synapse weight etc
class SynapseData extends Spike {
  val cacheAddressBase = cacheAxi4Config.addressType
  val offset = UInt(cacheLenWidth bits)
  val postNidBase = UInt(nidWidth bits)
  val data = Bits(busDataWidth bits)
  val weightValid = Bits(4 bits)
}

// pack stdp event
class SynapseEvent extends Bundle {
  val isLtd, isLtp = Bool()
  val ltdDeltaT, ltpDeltaT = UInt(log2Up(stdpTimeWindowWidth) bits)
}

class SynapseEventPacket extends SynapseData {
  val events = Vec(new SynapseEvent, 4)
}

case class SynapsePacket() extends SynapseEventPacket {
  val postParam = Bits(postParamBmbParameter.access.dataWidth bits)
}

case class AckSpike() extends Spike {
  val tagAddress = UInt(tagRamAddressWidth bits)
  val tagWayMask = Bits(Cache.wayCountPerStep bits)
  val dirty = Bool()
}

case class ThreadFlush() extends Bundle {
  val thread = UInt(log2Up(threads) bits)
  val len = UInt(cacheLenWidth bits)
}

class Synapse extends Component {
  val inKeep = slave(Stream(ReadySpike()))
  val outKeep = master(Stream(Bool()))
  //val bmbW = slave(Bmb(spikeTableBmbParameter))

  val dataPacker = new Area {
    val cacheDataPacker = new CacheDataPacker
    val synapseEventPacker = new SynapseEventPacker(1)

    inKeep >> cacheDataPacker.io.input
    cacheDataPacker.io.output >> synapseEventPacker.io.input
    outKeep <-/< synapseEventPacker.io.output.translateWith(synapseEventPacker.io.output.payload.asBits.xorR)

    val cacheBus = cacheDataPacker.io.cache
    val preSpikeTableBus = Handle(synapseEventPacker.io.preSpikeTableBus)
    val postSpikeTableBus = Handle(synapseEventPacker.io.postSpikeTableBus)

    val stubAxiW = Axi4WriteOnly(cacheAxi4Config)
    stubAxiW.setIdle()
  }

  val mem = new Area {
    val axiCrossBar = Axi4CrossbarFactory()
    implicit val bmbInterconnect = BmbInterconnectGenerator()

    val cacheBanks = Seq.fill(nCacheBank)(Axi4UramBank(busDataWidth, 32 KiB, 2))
    val preSpikeTable  = BmbOnChipRamGenerator(Handle(BmbAddress.preSpikeTable.base))
    val postSpikeTable = BmbOnChipRamGenerator(Handle(BmbAddress.postSpikeTable.base))
    //val postParamRam  = BmbOnChipRamGenerator(Handle(BmbAddress.postParam.base))
    preSpikeTable.size.load(BmbAddress.preSpikeTable.size)
    postSpikeTable.size.load(BmbAddress.postSpikeTable.size)
    //postParamRam.size.load(BmbAddress.postParam.size)


    for((bank, addrMap) <- cacheBanks.zip(GlobalAddress.cacheBanks)){
      axiCrossBar.addSlave(bank.io.axi, addrMap)
    }
    axiCrossBar.addConnection((dataPacker.cacheBus, cacheBanks.map(_.io.axi)))
    axiCrossBar.addConnection((dataPacker.stubAxiW, cacheBanks.map(_.io.axi)))
    axiCrossBar.build()

    bmbInterconnect.addMaster(
      Handle(spikeTableBmbParameter.access),
      bus = dataPacker.preSpikeTableBus
    )
    bmbInterconnect.addMaster(
      Handle(spikeTableBmbParameter.access),
      bus = dataPacker.postSpikeTableBus
    )
    //bmbInterconnect.addMaster(
    //  Handle(postParamBmbParameter.access),
    //  bus = dataPacker.postParamBus
    //)
    bmbInterconnect.addConnection(
      dataPacker.preSpikeTableBus,
      preSpikeTable.ctrl
    )
    bmbInterconnect.addConnection(
      dataPacker.postSpikeTableBus,
      postSpikeTable.ctrl
    )
    //bmbInterconnect.addConnection(
    //  dataPacker.postParamBus,
    //  postParamRam.ctrl
    //)
  }
}

object SynapseVerilog extends App {
  SpinalConfig().addStandardMemBlackboxing(blackboxByteEnables)
    .generateVerilog(new Synapse)
  GlobalAddress.printAddressMapping()
}