package lib.noc

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.core._
import spinal.lib._
import spinal.lib.sim._
import NoCDirection._

import scala.util.Random

case class FlitSim(raw: Long, last: Boolean){

  val destX = (raw >> 28).toInt
  val destY = (raw >> 24).toInt & 0xF
  val inDirection = NoCDirection((raw >> 16).toInt & 0xF)
  val srcX = (raw >> 12).toInt & 0xF
  val srcY = (raw >> 8).toInt  & 0xF

  def headPackBy(x:Int, y:Int): FlitSim = {
    val xDirection = (x < destX).toInt
    val yDirection = (y < destY).toInt
    val raw = this.raw | (xDirection<<23) | (yDirection<<22)
    FlitSim(raw, this.last)
  }

  def withSrc(x:Int, y:Int): FlitSim = {
    val raw = this.raw | (x << 12) | (y << 8)
    FlitSim(raw, this.last)
  }

  def withInDirection(direction: NoCDirection.SourceType): FlitSim ={
    val raw = this.raw | direction.id<<16
    FlitSim(raw, this.last)
  }

  def message ={
    s"from x${srcX}:y${srcY} to x${destX}:y${destY} raw:${raw.toHexString} last:${last}"
  }
}

case class PacketSim(
                    destX: Int,
                    destY: Int,
                    srcX: Int,
                    srcY: Int,
                    size: Int = 1,
                    withHeadPack:Boolean,
                    inDirection:NoCDirection.SourceType
                    ){

  val rawFlits = IndexedSeq.tabulate(size){ i =>
    val raw = (destX.toLong<<28)|(destY<<24)| i
    val last = i==size-1
    val flit = if(withHeadPack){
      FlitSim(raw, last) headPackBy(srcX, srcY) withInDirection inDirection
    }else {
      FlitSim(raw, last)
    }
    flit withSrc(srcX, srcY)
  }
}

case class PacketDriver(
                       port: Stream[Fragment[Bits]],
                       clockDomain: ClockDomain,
                       x: Int,
                       y: Int,
                       xSize: Int,
                       ySize: Int,
                       inDirection: NoCDirection.SourceType
                       ) {

  val (driver, cmdQueue) = StreamDriver.queue(port, clockDomain)
  // maxPacketSize could be set up to 256
  // because only 8 bits data field is reserved for sim
  val maxPacketSize = 32

  val (xRange, yRange) = inDirection match {
    case West  => (0 until xSize, y until ySize)
    case South => (0 to    x    , 0 until ySize)
    case East  => (0 until xSize, 0 to    y    )
    case North => (x until xSize, 0 until ySize)
    case Local => (0 until xSize, 0 until ySize)
  }

  val (srcX, srcY) = inDirection match {
    case West  => (x  , y-1)
    case South => (x+1, y  )
    case East  => (x  , y+1)
    case North => (x-1, y  )
    case Local => (x  , y  )
  }
  assert((0 to xSize).contains(srcX))
  assert((0 to ySize).contains(srcY))

  def randomGen(nPackets: Int, withHeadPack:Boolean = true):IndexedSeq[PacketSim] = {
    ( 0 until nPackets).map{ _ =>
      val packetSize = Random.nextInt(maxPacketSize) + 1
      val destX = xRange.randomPick()
      val destY = yRange.randomPick()
      val packet = PacketSim(destX, destY, srcX, srcY, packetSize, withHeadPack, inDirection)
      inject(packet)
      packet
    }
  }

  def inject(packet:PacketSim) = {
    for(rawFlit <- packet.rawFlits){
      injectFlit(rawFlit)
    }
  }

  def injectFlit(rawflit: FlitSim): Unit ={
    cmdQueue.enqueue{ flit =>
      flit.fragment #= rawflit.raw
      flit.last #= rawflit.last
    }
  }
}

case class PacketDirectionMonitor(
                        port: Stream[Fragment[Bits]],
                        clockDomain: ClockDomain,
                        x: Int,
                        y: Int,
                        outDirection: NoCDirection.SourceType
                        ){
  import Mesh._

  val (nextXRange, nextYRange) = outDirection match {
    case West => (0   to xMax, 0  until   y)
    case South  => (x+1 to xMax, 0   to  yMax)
    case East => (0   to xMax, y+1 to  yMax)
    case North  => (0 until   x, 0   to  yMax)
    case Local => (x to x,  y to y)
  }

  StreamReadyRandomizer(port, clockDomain)
  StreamMonitor(port, clockDomain){ payload =>
    val flit = FlitSim(
      raw  = payload.fragment.toLong,
      last = payload.last.toBoolean
    )
    val message = s"error flit ${flit.message} at x$x:y$y:$outDirection inPort:${flit.inDirection}"
    assert(nextXRange.contains(flit.destX), message)
    assert(nextYRange.contains(flit.destY), message)
  }
}

class UnpackTest extends AnyFunSuite {

  test("unpack test"){
    SimConfig
      .withWave
      .compile(new HeaderExtractor)
      .doSim{ dut =>
        dut.clockDomain.forkStimulus(2)
        StreamReadyRandomizer(dut.io.flit, dut.clockDomain)
        val packetDriver = PacketDriver(
          dut.io.raw,
          dut.clockDomain,
          x = 8,
          y = 8,
          xSize = Mesh.xMax,
          ySize = Mesh.yMax,
          inDirection = West
        )
        packetDriver.randomGen(8)
        dut.clockDomain.waitRisingEdge(10000)
      }
  }
}

class RouteDeMuxTest extends AnyFunSuite {

  def routeDeMuxTB(dut: RouteDeMux, direction:NoCDirection.SourceType): Unit ={
    dut.clockDomain.forkStimulus(2)
    for(d <- NoCDirection.values){
      PacketDirectionMonitor(dut.io.midPorts(d.id), dut.clockDomain, 8, 8, d)
    }
    val driver = PacketDriver(dut.io.raw, dut.clockDomain, x = 8, y = 8, Mesh.xMax, Mesh.yMax, direction)
    driver.randomGen(32)
    dut.clockDomain.waitRisingEdge(1024)
  }

  test("router deMux test"){
    for(d <- NoCDirection.values){
      SimConfig
        .compile(new RouteDeMux(8, 8, d))
        .doSim(901341682){ routeDeMuxTB(_, d)}
    }
  }
}

class RouterTest extends AnyFunSuite {

  def routerTB(dut: Router): Unit ={
    dut.clockDomain.forkStimulus(2)

    for(d <- NoCDirection.values){
      PacketDirectionMonitor(dut.io.outPorts(d.id), dut.clockDomain, 8, 8, d)
    }

    val drivers = NoCDirection.values.toList.sorted.map { d =>
      PacketDriver(dut.io.inPorts(d.id), dut.clockDomain, 8, 8, Mesh.xMax, Mesh.yMax, d)
    }

    for(driver <- drivers){
      driver.randomGen(1024)
    }
    dut.clockDomain.waitRisingEdge(100000)
  }

  test("Router Test"){
    SimConfig
      .compile(new Router(8, 8))
      .doSim{ routerTB _ }
  }
}

