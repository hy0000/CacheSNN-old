package lib.noc

import spinal.core._
import spinal.lib._

object Mesh {
  val xMax = 15
  val yMax = 15
  def apply(xSize:Int = xMax + 1,ySize:Int = yMax + 1 ) = new Mesh(xSize, ySize)
}

case class NocLocalIf() extends Bundle with IMasterSlave {
  val pop, push = Stream Fragment Bits(32 bits)
  val headerError = Bool()
  override def asMaster() = {
    master(push)
    slave(pop)
    in(headerError)
  }
}

class HeaderPacker(x:Int, y:Int, xSize:Int, ySize:Int) extends Component {
  val io = new Bundle {
    val raw = slave Stream Fragment(Bits(32 bits))
    val packed = master Stream Fragment(Bits(32 bits))
    val headerError = out Bool()
  }

  val destX = io.raw.payload(31 downto 28).asUInt
  val destY = io.raw.payload(27 downto 24).asUInt
  val xDirection = x < destX
  val yDirection = y < destY

  val coordinateOutOfRange = (destX > xSize || destY > ySize) && io.raw.isFirst
  val noneZeroPrivateField = io.raw.fragment(23 downto 16).orR && io.raw.isFirst
  io.headerError := coordinateOutOfRange || noneZeroPrivateField

  io.packed <-< io.raw
    .haltWhen(io.headerError)
    .translateWith{
      val ret = Fragment(Bits(32 bits))
      when(io.raw.isFirst){
        ret.fragment := destX ## destY ##
          xDirection ## yDirection ## B(0, 6 bits) ##
          io.raw.fragment(15 downto 0)
      }otherwise{
        ret.fragment := io.raw.fragment
      }
      ret.last := io.raw.last
      ret
    }

  when(io.raw.valid){
    assert(!io.headerError, s"header error occur at x$x:y$y")
  }
}

class Mesh(xSize:Int, ySize:Int) extends Component {
  import NoCDirection._

  val localIfs = Seq.tabulate(xSize, ySize){ (_,_) =>
    slave(NocLocalIf())
  }

  for(localIf <- localIfs.flatten){
    localIf.pop.fragment.setPartialName("")
    localIf.push.fragment.setPartialName("")
  }

  val routers = Seq.tabulate(xSize, ySize){ (x, y) =>
    new Router(x, y)
  }

  def getEdgePorts(getPorts: Router=>Vec[Stream[Fragment[Bits]]]) = {
    routers(0).map(getPorts(_)(North.id)) ++
      routers(xSize-1).map(getPorts(_)(South.id)) ++
      routers.map(_(0)).map(getPorts(_)(West.id)) ++
      routers.map(_(ySize-1)).map(getPorts(_)(East.id))
  }

  val routerInEdges = getEdgePorts(router => router.io.inPorts)
  val routerOutEdges = getEdgePorts(router => router.io.outPorts)
  for(p <- routerInEdges) {
    p.fragment := 0
    p.last := False
    p.valid := False
  }
  for(p <- routerOutEdges) {
    p.ready := False
  }

  for(row <- routers){
    row.reduce{(rLeft, rRight) =>
      rLeft.io.inPorts(East.id) <-/< rRight.io.outPorts(West.id)
      rLeft.io.outPorts(East.id) >/-> rRight.io.inPorts(West.id)
      rRight
    }
  }

  for(col <- routers.transpose){
    col.reduce{(rUp, rDown) =>
      rUp.io.inPorts(South.id) <-/< rDown.io.outPorts(North.id)
      rUp.io.outPorts(South.id) >/-> rDown.io.inPorts(North.id)
      rDown
    }
  }

  val localClockDomain = ClockDomain.external("local")

  val local = new ClockingArea(localClockDomain){
    val headerPackers = Seq.tabulate(xSize, ySize){ (x, y) =>
      new HeaderPacker(x, y, xSize, ySize)
    }
  }

  Seq.tabulate(xSize, ySize){ (x, y) =>
    val localIf = localIfs(x)(y)
    val headerPacker = local.headerPackers(x)(y)
    val router = routers(x)(y)

    val localInFifoCC = StreamFifoCC(
      dataType = Fragment(Bits(32 bits)),
      depth = 16,
      pushClock = localClockDomain,
      popClock = clockDomain
    )
    val localOutFifoCC = StreamFifoCC(
      dataType = Fragment(Bits(32 bits)),
      depth = 16,
      pushClock = clockDomain,
      popClock = localClockDomain
    )

    localIf.push           >> headerPacker.io.raw
    localInFifoCC.io.push  << headerPacker.io.packed
    localInFifoCC.io.pop   >> router.io.inPorts(Local.id)
    localOutFifoCC.io.push << router.io.outPorts(Local.id)
    localOutFifoCC.io.pop  >> localIf.pop

    localIf.headerError := headerPacker.io.headerError
  }
}

object MeshVerilog extends App {
  SpinalVerilog(new Mesh(2, 8))
}