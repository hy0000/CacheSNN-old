package cachesnn


import spinal.core._
import spinal.lib._
import cachesnn.AerBus._
import cachesnn.Synapse._
import cachesnn.Stdp._
import spinal.lib.bus.bmb
import spinal.lib.bus.bmb.BmbParameter.BurstAlignement.WORD
import spinal.lib.bus.bmb._
import spinal.lib.bus.regif.BusInterface

object Stdp {
  val busDataWidth = 32
  val spikeBufferSize = 4 KiB
  val stdpFpuMemSize = 4 KiB
  val neuronByteCount = 2
  val maxNeuronNum = spikeBufferSize/neuronByteCount
  val neuronIdWidth = log2Up(maxNeuronNum)
}

object Action extends SpinalEnum {
  val queryOnly, queryInsert, insertOnly, update, busRead, busWrite = newElement()
}

trait Spikes extends Bundle {
  val spikes = Bits(stdpTimeWindowWidth bits)
}

class SpikeQueryEvent(isPost:Boolean) extends Bundle {
  val neuronId = UInt(neuronIdWidth bits)
  val virtualSpike = ifGen(!isPost)(Bool())
  val offset = ifGen(isPost)(UInt(neuronIdWidth bits))
  val weight = ifGen(isPost)(Bits(weightWidth bits))
}

case class SpikesRsp(isPost:Boolean) extends SpikeQueryEvent(isPost) with Spikes

class SpikesTable(p: BmbParameter, size:BigInt, isPost:Boolean) extends Component {
  val io = new Bundle {
    val queryCmd = slave Stream new SpikeQueryEvent(isPost)
    val queryRsp = master Stream new SpikesRsp(isPost)
    val bus = slave(Bmb(p))
  }

  val spikeRam = Mem(Bits(32 bits), size/p.access.byteCount)

  case class SpikesTableDataPath(p: BmbParameter, isPost:Boolean) extends Bundle {
    val address = UInt(p.access.addressWidth-p.access.wordRangeLength bits)
    val neuronIdLow = UInt(log2Up(neuronByteCount) bits)
    val data = Bits(p.access.dataWidth bits)
    val mask = Bits(p.access.maskWidth bits)
    val action = Action()
    // below are bmb bus fields
    val source = UInt(p.access.sourceWidth bits)
    val context = Bits(p.access.contextWidth bits)
    // below are axon fields
    val offset = ifGen(isPost)(UInt(neuronIdWidth bits))
    val weight = ifGen(isPost)(Bits(weightWidth bits))

    def neuronId:UInt = address @@ neuronIdLow
    def isEffectiveContext:Bool = 0=/=extractSrcContext(context)
    def dataDivided:Vec[Bits] = data.subdivideIn(stdpTimeWindowWidth bits)
    def spikes:Bits = dataDivided(neuronIdLow)
    def spikesDo(action: Bits => Bits): Bits = dataDivided.map(action).asBits()
    def setMaskByNeuronIdLow(): Unit ={
      val m = B((1<<neuronByteCount)-1, neuronByteCount bits)
      mask := (m<<(neuronIdLow*2)).resized
    }
  }

  val cmdFromQuery = io.queryCmd.translateWith{
    val ret = SpikesTableDataPath(p, isPost)
    ret.assignSomeByName(io.queryCmd.payload)
    ret.address := io.queryCmd.neuronId.dropLow(log2Up(neuronByteCount)).asUInt
    ret.neuronIdLow := io.queryCmd.neuronId.resized
    ret.setMaskByNeuronIdLow()
    ret.data := 0
    ret.context := 0
    ret.source := 0
    if(isPost){
      ret.action := Action.queryOnly
    }else{
      when(io.queryCmd.virtualSpike){
        ret.action := Action.queryOnly
      }otherwise{
        ret.action := Action.queryInsert
      }
    }
    ret
  }

  val cmdFromBus = io.bus.cmd.translateWith{
    val ret = SpikesTableDataPath(p, isPost)
    ret.assignSomeByName(io.bus.cmd.fragment)
    ret.address.removeAssignments()
    ret.address := io.bus.cmd.address.dropLow(p.access.wordRangeLength).asUInt
    ret.neuronIdLow := io.bus.cmd.address.resized
    if(isPost){
      ret.weight := 0
      ret.offset := 0
    }
    when(io.bus.cmd.isWrite){
      ret.action := Action.busWrite
      if(isPost){
        when(ret.isEffectiveContext){
          ret.action := Action.insertOnly
          ret.address := io.bus.cmd.address.dropLow(log2Up(neuronByteCount)).asUInt.resized
          ret.setMaskByNeuronIdLow()
        }
      }
    } elsewhen ret.isEffectiveContext{
      ret.action := Action.update
    } otherwise{
      ret.action := Action.busRead
    }
    ret
  }

  val cmd = StreamArbiterFactory.lowerFirst.on(
    Seq(cmdFromBus, cmdFromQuery)
  )

  val (busRsp, queryRsp, writeBack) = {
    val (readCmd, pass) = StreamFork2(cmd)
    val readRsp = spikeRam.streamReadSync(
      readCmd.translateWith(readCmd.address)
    )
    val passStaged = pass.stage()
    val spikeReadOut = StreamJoin(readRsp, passStaged).translateWith{
      val ret = SpikesTableDataPath(p, isPost)
      ret.assignSomeByName(passStaged.payload)
      when(passStaged.action=/=Action.busWrite){
        ret.data := readRsp.payload
      }
      ret
    }

    val rspFork = StreamFork2(spikeReadOut)
    (rspFork._1, rspFork._2, spikeReadOut.asFlow)
  }

  val _ = new Area {
    import Action._

    io.queryRsp << queryRsp.takeWhen(
      Seq(queryOnly, queryInsert).map(_===queryRsp.action).orR
    ).translateWith{
      val ret = SpikesRsp(isPost)
      ret.assignSomeByName(queryRsp.payload)
      ret.spikes := queryRsp.spikes
      ret.neuronId := queryRsp.neuronId
      if(!isPost){
        ret.virtualSpike := queryRsp.action===queryOnly
        ret.spikes(0) := !ret.virtualSpike
      }
      ret
    }

    io.bus.rsp << busRsp.takeWhen(
      Seq(busRead, busWrite, update, insertOnly).map(_===busRsp.action).orR
    ).translateWith{
      val ret = io.bus.rsp.copy()
      ret.assignSomeByName(busRsp.payload)
      ret.setSuccess()
      ret
    }.addFragmentLast(True)

    spikeRam.writePortWithMask << writeBack.takeWhen(
      Seq(busWrite, queryInsert, update, insertOnly).map(_===writeBack.action).orR
    ).translateWith{
      val ret = MemWriteCmdWithMask(spikeRam)
      ret.mask := writeBack.mask
      ret.address := writeBack.address
      when(writeBack.action===queryInsert){
        ret.data := writeBack.spikesDo(spikes => spikes | B(1))
      }elsewhen(writeBack.action===insertOnly){
        ret.data := writeBack.spikesDo(spikes => spikes | B(2))
      }elsewhen(writeBack.action===update){
        ret.data := writeBack.spikesDo(spikes => spikes |<< 1)
      }otherwise{
        ret.data := writeBack.data
      }
      ret
    }
  }
}

case class StdpEvent() extends SpikeQueryEvent(true){
  val isLtd, isLtp = Bool()
  val ltdDeltaT, ltpDeltaT = UInt(log2Up(stdpTimeWindowWidth) bits)
  val preNeuronId = UInt(neuronIdWidth bits)
  def postNeuronId:UInt = neuronId
}

class StdpEventGen extends Component {
  val io = new Bundle {
    val preSpikes = slave Stream SpikesRsp(isPost = false)
    val postSpikes = slave Stream SpikesRsp(isPost = true)
    val stdpEvent = master Stream StdpEvent()
  }

  case class OhSpikes() extends SpikeQueryEvent(true){
    val ohPrePreSpikes = Bits(stdpTimeWindowWidth bits)
    val ohLtpPostSpikes = Bits(stdpTimeWindowWidth bits)
    val ohLtdPostSpikes = Bits(stdpTimeWindowWidth bits)
    override val virtualSpike = Bool()
    val preNeuronId = UInt(neuronIdWidth bits)
  }

  val ohSpikes = StreamJoin(io.preSpikes, io.postSpikes).translateWith{
    val ret = OhSpikes()
    ret.assignSomeByName(io.postSpikes.payload)
    val ohPrePreSpikes = OHMasking.first(io.preSpikes.spikes.dropLow(1)) ## B"0"
    val ppsMask = (ohPrePreSpikes.asUInt-1).asBits | ohPrePreSpikes
    val postSpikeMasked = io.postSpikes.spikes & ppsMask
    ret.ohPrePreSpikes := ohPrePreSpikes
    ret.ohLtpPostSpikes := OHMasking.roundRobin(
      postSpikeMasked.reversed, (ohPrePreSpikes<<1).reversed
    ).reversed
    ret.ohLtdPostSpikes := OHMasking.first(postSpikeMasked)
    ret.virtualSpike := io.preSpikes.virtualSpike
    ret.preNeuronId := io.preSpikes.neuronId
    ret
  }.stage()

  io.stdpEvent <-< ohSpikes.translateWith {
    val ret = StdpEvent()
    ret.assignSomeByName(ohSpikes.payload)
    val prePreSpikeTime = OHToUInt(ohSpikes.ohPrePreSpikes)
    ret.isLtd := ohSpikes.ohLtdPostSpikes.orR && (!ohSpikes.virtualSpike)
    ret.isLtp := ohSpikes.ohLtpPostSpikes.orR
    when(ret.isLtp){
      ret.ltpDeltaT := prePreSpikeTime - OHToUInt(ohSpikes.ohLtpPostSpikes)
    }otherwise{
      ret.ltpDeltaT := 0
    }
    when(ret.isLtd){
      ret.ltdDeltaT := OHToUInt(ohSpikes.ohLtdPostSpikes)
    }otherwise{
      ret.ltdDeltaT := 0
    }
    ret
  }
}

object StdpVerilog extends App {
  //SpinalVerilog(new SpikesTable(bmbParameter, 4 KiB, false))
  SpinalVerilog(new StdpEventGen)
}