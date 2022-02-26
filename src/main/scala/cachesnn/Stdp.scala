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
/*
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

object Action extends SpinalEnum {
  val busRead, busWrite, fence1, fence2= newElement()
  val preSpikeQuery, virtualQuery, update, postSpikeWrite = newElement()
}

trait Action {
  val action = Action()
}

class PreSpikeCmd extends Bundle with Action{
  val preNeuronId = UInt(neuronIdWidth bits)
}
class PreSpikeRsp extends Bundle with Action{
  val prePreSpikeTime = UInt(log2Up(stdpTimeWindowWidth) bits)
}
class PostSpikeCmd extends Axon with Action
class PostSpikeRsp extends Axon with Action{
  val postSpikeTime = UInt(log2Up(stdpTimeWindowWidth) bits)
}

class PreSpikeBuffer extends Component {
  val io = new Bundle {
    val cmd = slave Stream new PreSpikeCmd
    val rsp = master Stream new PreSpikeRsp
    val fence = master Stream new PreSpikeCmd
    val bus = slave(Bmb(bmbParameter))
  }

  case class DataPath() extends PreSpikeCmd {
    val data = Bits(busDataWidth bits)

    def spikes:Bits = {
      preNeuronId(0) ? data(31 downto 16) | data(15 downto 0)
    }
  }

  val addressCounter = Counter(bufferAddressWidth bits)
  val spikeBuffer = Mem(Bits(32 bits), maxNeuronNum)

  val cmdFilter = new Area {
    val busy = Reg(Bool()) init False
    val fence1 = io.cmd.action === Action.fence1
    busy riseWhen io.cmd.fire && fence1
    busy fallWhen addressCounter.willOverflow

    val updateCmd = Stream(new PreSpikeCmd)
    updateCmd.valid := busy
    updateCmd.preNeuronId := addressCounter
    updateCmd.action := Action.update
    when(updateCmd.fire){
      addressCounter.increment()
    }
    val queryCmd = io.cmd.throwWhen(fence1).haltWhen(busy)
    val output = StreamArbiterFactory.lowerFirst.on(
      Seq(updateCmd, queryCmd)
    )
  }

  when(io.bus.cmd.fire){
    when(io.bus.cmd.length+io.bus.cmd.address===addressCounter){
      addressCounter.clear()
    }otherwise{
      addressCounter.increment()
    }
  }

  val cmdFromCmdFilter = cmdFilter.output.translateWith{
    val ret = DataPath()
    ret.assignSomeByName(cmdFilter.output)
    ret.data := 0
    ret
  }
  val cmdFromBus = io.bus.cmd.translateWith{
    val ret = DataPath()
    ret.data := io.bus.cmd.data
    ret.preNeuronId := io.bus.cmd.address + addressCounter
    ret.action := io.bus.cmd.isRead ? Action.busRead | Action.busWrite
    ret
  }

  val cmd = StreamArbiterFactory.lowerFirst.on(
    Seq(cmdFromCmdFilter, cmdFromBus)
  ).stage()

  val (readRsp, axon) = {
    val ret = StreamFork2(cmd)
    val readRsp = spikeBuffer.streamReadSync(
      ret._1.translateWith(ret._1.preNeuronId(addressRange))
    )
    val axon = ret._2.stage()
    (readRsp, axon)
  }

  val output = StreamJoin(readRsp, axon).translateWith{
    val ret = DataPath()
    ret.assignSomeByName(axon)
    when(ret.action===Action.busRead){
      ret.data := readRsp.payload
    }
    ret
  }

  val (toRsp, toBus, toFence) = {
    import Action._
    val selRsp = Seq(preSpikeQuery, virtualQuery, fence2)
    val selBus = Seq(busRead, busWrite)
    val selFence = Seq(fence1, update)
    val sel = Seq(selRsp, selBus, selFence).map(
      _.map(_===output.action).orR
    )

    val ret = StreamDemux(output, OHToUInt(sel), 3)
    (ret(0), ret(1), ret(2))
  }

  io.rsp << toRsp.translateWith{
    val ret = new PreSpikeRsp
    val oldSpikes = (toRsp.spikes>>1) ## B"0"
    ret.prePreSpikeTime := OHToUInt(OHMasking.first(oldSpikes))
    ret
  }

  io.bus.rsp << toBus.translateWith{
    val ret = io.bus.rsp.copy()
    ret.setSuccess()
    ret.source := RegNext(io.bus.cmd, io.bus.cmd.ready)
    ret.context := 0
    when(toBus.action===Action.busRead){
      ret.data := toBus.data
    }otherwise{
      ret.data := 0
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

class SpikeQueryRsp extends SpikeQueryPipe{
  val postSpikeTime = Bits(log2Up(stdpTimeWindowWidth) bits)
}

case class StdpEvent() extends Axon {
  val isLtp = Bool()
  val deltaT = UInt(log2Up(stdpTimeWindowWidth) bits)
}
*/
class SpikeTableUpdater extends Component {
  val io = new Bundle {

  }
}


/*
class PostSpikeBuffer(baseAddress: Int) extends  Component {
  val io = new Bundle {
    val queryCmd = slave(Stream(new PreSpikeQueryRsp))
    val queryRsp = master(Stream(new SpikeQueryRsp))
    val postSpike = slave(Stream(UInt(neuronIdWidth bits)))
    val bus = slave(Bmb(bmbParameter))
  }

  //val busif = BusInterface(
  //  io.bus.toWishbone(),
  //  (baseAddress, 4 Byte)
  //)
  //val neuronNum = busif.newReg(doc="neuron num")
  val neuronNum = Reg(UInt(16 bits)) init 0
  val spikeBuffer = Mem(Bits(32 bits), maxNeuronNum)

  val addrCounter = Counter(spikeBuffer.addressWidth bits)

  val (queryFork, updateFork) = {
    val cmdFork = StreamFork2(io.queryCmd)
    (
      cmdFork._1.throwWhen(io.queryCmd.fence),
      cmdFork._2.takeWhen(io.queryCmd.fence)
    )
  }

  val update = new Area{
    val addressIncr = Counter(spikeBuffer.addressWidth bits)
    val cmd = Stream(UInt(spikeBuffer.addressWidth bits))
    val busy = Reg(Bool()) init False
    busy riseWhen updateFork.fire
    busy fallWhen addressIncr.willOverflow
    when(cmd.fire){
      addressIncr.increment()
    }
    cmd.valid := busy
    cmd.payload := addressIncr
    updateFork.ready := !busy //TODO: thinking
  }

  val readChannel = new Area {
    class Pipe extends Axon with SpikeAction{
      val address = UInt(spikeBuffer.addressWidth bits)
      val data = Bits(32 bits)

      def postSpikes: Bits = {
        address(0) ? data(31 downto 16) | data(15 downto 0)
      }
    }

    val readByQuery = queryFork.translateWith{
      val ret = new Pipe()
      ret.assignSomeByName(queryFork) // set the axon field
      ret.address := queryFork.postNeuronId(neuronAddrRange)
      ret.data := 0
      ret
    }
    val readByBus = io.bus.cmd.translateWith{
      val isRead = io.bus.cmd.isRead
      val ret = new Pipe()
      ret.setAxonDefaultValue()
      ret.action := isRead ? ActionType.busRead | ActionType.busWrite
      ret.address := io.bus.cmd.address(busAddrRange)
      when(isRead){
        ret.data := 0
      }otherwise {
        ret.data := io.bus.cmd.data
      }
      ret
    }
    val readByPostSpike = io.postSpike.translateWith{
      val ret = new Pipe()
      ret.setAxonDefaultValue()
      ret.action := ActionType.writePostSpike
      ret.address := io.postSpike.payload(neuronAddrRange)
      ret.data := 0
      ret
    }
    val readByUpdate = update.cmd.translateWith{
      val ret = new Pipe()
      ret.setAxonDefaultValue()
      ret.action := ActionType.update
      ret.address := update.cmd.payload
      ret.data := 0
      ret
    }

    val (spikes, axonInfo) = {
      val readPorts = Seq(
        readByUpdate,
        readByPostSpike,
        readByBus,
        readByQuery
      )
      val (readCmd, axonInfo) = StreamFork2(
        StreamArbiterFactory.lowerFirst.on(readPorts).stage(),
        synchronous = true
      )
      val spikes = spikeBuffer.streamReadSync(
        readCmd.translateWith(readCmd.address)
      )
      (spikes, axonInfo.stage())
    }

    val bufferRsp = StreamJoin(spikes, axonInfo).translateWith{
      val ret = new Pipe
      ret.assignSomeByName(axonInfo)
      when(ret.action=/=ActionType.busWrite){
        ret.data := spikes.payload
      }
      ret
    }.s2mPipe()

    val (queryRsp, busReadRsp) = {
      val toBus   = U"0"
      val toQuery = U"1"
      val outPortsSel = (bufferRsp.action===ActionType.busRead) ? toBus | toQuery
      val outPorts = StreamDemux(bufferRsp, outPortsSel, 2)

      val busRsp = outPorts(toBus).translateWith{
        val ret = io.bus.rsp.copy()
        ret.setSuccess()
        ret.data := outPorts(toBus).data
        ret.source := RegNextWhen(io.bus.cmd.source, io.bus.cmd.ready)
        ret.context := 0
        ret
      }.addFragmentLast(True) //TODO: ....

      val queryRsp = outPorts(toQuery).translateWith{
        val ret = new SpikeQueryRsp
        ret.assignSomeByName(outPorts(toQuery))
        ret.postSpikes := outPorts(toQuery).address(0) ?
          outPorts(toQuery).data(31 downto 16) | outPorts(toQuery).data(15 downto 0)
        ret
      }
      (queryRsp, busRsp)
    }
    io.queryRsp << queryRsp
    io.bus.rsp << busReadRsp
  }

  val bufferRsp = readChannel.bufferRsp
  spikeBuffer.writePortWithMask << bufferRsp.asFlow.takeWhen(
    Seq(
      ActionType.update,
      ActionType.writePostSpike,
      ActionType.busWrite
    ).map(_===bufferRsp.action).orR
  ).translateWith{
    val ret = MemWriteCmdWithMask(spikeBuffer)
    val address = bufferRsp.postNeuronId(neuronAddrRange)
    val spikes1 = bufferRsp.data(31 downto 16)
    val spikes0 = bufferRsp.data(15 downto 0)
    val action = bufferRsp.action
    when(action===ActionType.update){
      ret.data := (spikes1 |<< 1) ##  (spikes0 |<< 1)
      ret.mask := B"11"
    }elsewhen(action===ActionType.writePostSpike){
      ret.data := (spikes1 | B(1, 16 bits)) ## (spikes0 | B(1, 16 bits))
      ret.mask := address(0) ? B"01" | B"10"
    }elsewhen(action===ActionType.busWrite){
      ret.data := spikes1 ## spikes0
      ret.mask := B"11"
    }otherwise{
      ret.data := 0
      ret.mask := B"00"
    }
    ret
  }
}


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
  //SpinalVerilog(new PreSpikeBuffer)
}