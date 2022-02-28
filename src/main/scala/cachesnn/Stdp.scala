package cachesnn


import spinal.core._
import spinal.lib._
import cachesnn.AerBus._
import cachesnn.Synapse._
import cachesnn.Stdp._
import spinal.lib.bus.bmb.BmbParameter.BurstAlignement.WORD
import spinal.lib.bus.bmb._
import spinal.lib.bus.regif.BusInterface

object Stdp {
  val busDataWidth = 32
  val spikeBufferSize = 4 KiB
  val stdpFpuMemSize = 4 KiB
  val maxNeuronNum = spikeBufferSize/stdpTimeWindowWidth
  val neuronIdWidth = log2Up(maxNeuronNum)
  val bufferAddressWidth = neuronIdWidth>>(busDataWidth/stdpTimeWindowWidth)
  val addressRange = bufferAddressWidth downto 1
}

object Action extends SpinalEnum {
  val queryOnly, queryInsert, insertOnly, update, busRead, busWrite = newElement()
}

class Axon extends Bundle {
  val weight = Bits(weightWidth bits)
  val postNeuronId = UInt(neuronIdWidth bits)
  val offset = UInt(neuronIdWidth bits)

  def setAxonDefaultValue(): Unit ={
    weight := 0
    postNeuronId := 0
    offset := 0
  }
}

class PreSpikeCmd extends Bundle {
  val preNeuronId = UInt(neuronIdWidth bits)
  val virtualSpike = Bool()
}

class PreSpikeRsp extends Bundle {
  val prePreSpikeTime = UInt(log2Up(stdpTimeWindowWidth) bits)
  val virtualSpike = Bool()
}

case class SpikeTableDataPath(p: BmbParameter) extends Bundle {
  val address = UInt(p.access.addressWidth bits)
  val data = Bits(p.access.dataWidth bits)
  val mask = Bits(p.access.maskWidth bits)
  val action = Action()
  // below are bmb bus fields
  val source = UInt(p.access.sourceWidth bits)
  val context = Bits(p.access.contextWidth bits)

  def neuronIdLow:UInt = mask(log2Up(p.access.dataWidth/stdpTimeWindowWidth)-1 downto 0).asUInt
  def neuronId:UInt = address @@ neuronIdLow
  def isUpdateContext:Bool = {
    True
  }
  def dataDivided:Vec[Bits] = data.subdivideIn(stdpTimeWindowWidth bits)
  def spikes:Bits = dataDivided(neuronIdLow)
  def spikeInserted:Bits = spikes | B(1)
  def spikeUpdated:Bits = spikes |<< 1
}

class PostSpikeRsp extends Axon{
  val postSpikeTime = UInt(log2Up(stdpTimeWindowWidth) bits)
}

class PreSpikeTable(p: BmbParameter, size:BigInt) extends Component {
  val io = new Bundle {
    val queryCmd = slave Stream new PreSpikeCmd
    val queryRsp = master Stream new PreSpikeRsp
    val bus = slave(Bmb(p))
  }

  val spikeBuffer = Mem(Bits(32 bits), size/p.access.byteCount)

  val cmdFromQuery = io.queryCmd.translateWith{
    val ret = new SpikeTableDataPath(p)
    ret.address := (io.queryCmd.preNeuronId >> p.access.wordRangeLength).resized
    ret.data := 0
    // using mask to retain neuronId low bits
    ret.mask := io.queryCmd.preNeuronId(p.access.wordRangeLength-1 downto 0).asBits.resized
    ret.source := 0
    ret.context := 0
    when(io.queryCmd.virtualSpike){
      ret.action := Action.queryOnly
    }otherwise{
      ret.action := Action.queryInsert
    }
    ret
  }

  val cmdFromBus = io.bus.cmd.translateWith{
    val ret = SpikeTableDataPath(p)
    ret.assignSomeByName(io.bus.cmd.fragment)
    ret.address.removeAssignments()
    ret.address := (io.queryCmd.preNeuronId >> p.access.wordRangeLength).resized
    when(io.bus.cmd.isWrite){
      ret.action := Action.busWrite
    } elsewhen ret.isUpdateContext {
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
    val cmdFork = StreamFork2(cmd)
    val readRsp = spikeBuffer.streamReadSync(
      cmdFork._1.translateWith(cmdFork._1.address)
    )
    val cmdStaged = cmdFork._2.stage()
    val spikeReadOut = StreamJoin(readRsp, cmdStaged).translateWith{
      val ret = SpikeTableDataPath(p)
      ret.assignSomeByName(cmdStaged.payload)
      when(cmdStaged.action=/=Action.busWrite){
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
      val ret = new PreSpikeRsp
      ret.virtualSpike := queryRsp.action===queryOnly
      ret.prePreSpikeTime := OHToUInt(queryRsp.spikes)
      ret
    }

    io.bus.rsp << busRsp.takeWhen(
      Seq(busRead, busWrite, update).map(_===busRsp.action).orR
    ).translateWith{
      val ret = io.bus.rsp.copy()
      ret.assignSomeByName(busRsp.payload)
      ret.setSuccess()
      when(busRsp.isUpdateContext){
        ret.data := busRsp.neuronId.asBits.resized
        ret.data.msb := busRsp.spikes.msb
      }otherwise{
        ret.data := busRsp.data
      }
      ret
    }.addFragmentLast(True)

    spikeBuffer.writePortWithMask(p.access.maskWidth) << writeBack.takeWhen(
      Seq(busWrite, queryInsert, update).map(_===writeBack.action).orR
    ).translateWith{
      val ret = MemWriteCmdWithMask(spikeBuffer, p.access.maskWidth)
      ret.mask := writeBack.mask
      ret.address := writeBack.address
      ret.data := writeBack.data
      val vData = ret.data.subdivideIn(stdpTimeWindowWidth bits)
      when(writeBack.action===queryInsert){
        vData(writeBack.neuronIdLow) := writeBack.spikeInserted
      }elsewhen(writeBack.action===update){
        vData(writeBack.neuronIdLow) := writeBack.spikeUpdated
      }
      ret
    }
  }
}

class SpikeQueryPipe extends Axon{
  val data = UInt(32 bits)

  // when preSpikeQuery and virtualQuery at preSpikeTable
  def preNeuronId:UInt = data(neuronIdWidth downto 0)
  // when update
  def address:UInt = data(bufferAddressWidth downto 0)
  // when bus
  def length:UInt  = data(bufferAddressWidth downto 0)
  def prePreSpikeTime:UInt = data(log2Up(stdpTimeWindowWidth)-1 downto 0)
}

case class StdpEvent() extends Axon {
  val isLtp = Bool()
  val deltaT = UInt(log2Up(stdpTimeWindowWidth) bits)
}

/*
class StdpCore extends Component {
  val io = new Bundle {
    val axon = Vec(slave(Stream(new Axon)), channels)
    val preSpikeCmd = slave Stream new PreSpikeCmd
    val fenceAck = master Stream new PreSpikeCmd
    val events = Vec(master(Stream(StdpEvent())), channels)
  }
}
*/
object StdpVerilog extends App {
  //val p = BmbOnChipRam.busCapabilities(4 KiB, dataWidth = 32)
  SpinalVerilog(new PreSpikeTable(bmbParameter, 4 KiB))
}