package lib.noc

import spinal.core._
import spinal.lib._

object NoCDirection extends Enumeration{
  type SourceType = Value
  val North = Value(0, "north")
  val East = Value(1, "east")
  val South = Value(2, "south")
  val West = Value(3, "west")
  val Local = Value(4, "local")
}

case class Header() extends Bundle {
  val destX, destY = UInt(4 bits)
  val xDirection, yDirection = Bool()
}

case class Flit() extends Bundle {
  val data = Bits(32 bits)
  val header = Header()
}

class HeaderExtractor extends Component {
  val io = new Bundle{
    val raw = slave Stream Fragment(Bits(32 bits))
    val flit = master Stream Fragment(Flit())
  }

  val header = Reg(Header())
  when(io.raw.isFirst && io.raw.ready) {
    header.destX := io.raw.payload(31 downto 28).asUInt
    header.destY := io.raw.payload(27 downto 24).asUInt
    header.xDirection := io.raw.payload(23)
    header.yDirection := io.raw.payload(22)
  }

  val rawPiped = io.raw.stage()

  io.flit << rawPiped.translateWith{
    val ret = Fragment(Flit())
    ret.last := rawPiped.last
    ret.header := header
    ret.data := rawPiped.fragment
    ret
  }
}

class RouteDeMux(x:Int, y:Int, inDirection: NoCDirection.SourceType ) extends Component {
  import NoCDirection._

  val io = new Bundle {
    val raw = slave Stream Fragment(Bits(32 bits))
    val midPorts = Vec(master Stream Fragment(Bits(32 bits)), 5)
  }

  val headerExtractor = new HeaderExtractor()
  headerExtractor.io.raw << io.raw
  val flit = headerExtractor.io.flit

  val xArrived = flit.header.destX===x
  val yArrived = flit.header.destY===y

  val directionValid = Vec(Bool(),5)
  directionValid(Local.id) :=  xArrived &&  yArrived
  directionValid(South.id) := !xArrived &&  flit.header.xDirection
  directionValid(West.id)  := !yArrived && !flit.header.yDirection
  directionValid(North.id) := !xArrived && !flit.header.xDirection
  directionValid(East.id)  := !yArrived &&  flit.header.yDirection


  val maskOptional = Vec(Bool(),5)
  maskOptional := directionValid

  if(inDirection!=Local){
    maskOptional(inDirection.id).removeAssignments()
    maskOptional(inDirection.id) := False

    when(flit.isFirst){
      assert(
        assertion = !directionValid(inDirection.id),
        message = s"packet at x$x:y$y:$inDirection want to route back"
      )
    }
  }

  val ohPriority = Bits(5 bits) setAsReg() init 0x1
  val ohMaskProposal = OHMasking.roundRobin(maskOptional, ohPriority.asBools)

  val ohMaskLocked = Reg(Vec(Bool(),5))
  val locked = ohMaskLocked.orR
  for((p, i) <- io.midPorts.zipWithIndex){
    ohMaskLocked(i) init False
    ohMaskLocked(i) setWhen p.fire
    ohMaskLocked(i) clearWhen(p.fire && p.last)
  }

  when(!locked && flit.valid){
    ohPriority := ohPriority(3 downto 0) ## ohPriority.msb
  }

  val ohMaskRouted = Mux(locked, ohMaskLocked, ohMaskProposal)

  val sel = OHToUInt(ohMaskRouted)

  val raw = flit.translateWith{
    val ret = Fragment(flit.data)
    ret.fragment := flit.data
    ret.last := flit.last
    ret
  }

  io.midPorts <> StreamDemux(raw, sel, 5)
}

class Router(x:Int, y:Int) extends Component {
  val io = new Bundle {
    val inPorts = Vec(
      slave Stream Fragment(Bits(32 bits)), 5
    )
    val outPorts = Vec(
      master Stream Fragment(Bits(32 bits)), 5
    )
  }

  val directions = NoCDirection.values.toList.sorted

  val midPorts = io.inPorts
    .zip(directions)
    .flatMap{ case(p, d)=>
      val routeDeMux = new RouteDeMux(x, y, d)
      routeDeMux.io.raw </< p
      routeDeMux.io.midPorts
    }

  io.outPorts
    .zip(directions)
    .foreach{ case(p, d)=>
      val midPortIds = (d.id until midPorts.length) by 5
      p <-/< StreamArbiterFactory.fragmentLock.roundRobin.on(
          midPortIds.map(midPorts(_))
        )
    }
}

object Router extends App {
  SpinalVerilog(new Router(8 ,8))
}
