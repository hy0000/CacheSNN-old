package lib.noc

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.sim._
import spinal.core._
import spinal.lib._
import NoCDirection._

import scala.collection.mutable

case class PacketRecvMonitor(
                             port: Stream[Fragment[Bits]],
                             clockDomain: ClockDomain,
                             x: Int,
                             y: Int,
                             xSize: Int,
                             ySize: Int
                            ) {
  // define recvQueue for every source node
  val recvQueue = Seq.tabulate(xSize, ySize)(
    (_,_) => new mutable.Queue[FlitSim]
  )

  StreamReadyRandomizer(port, clockDomain)

  StreamMonitor(port, clockDomain){ payload =>
    val flit = FlitSim(
      raw  = payload.fragment.toLong & 0xff00ffffL, // restore private field
      last = payload.last.toBoolean
    )

    assert(
      recvQueue(flit.srcX)(flit.srcY).nonEmpty,
      s"""empty recvQueue but received flit
        node x$x:y$y received ${flit.message}"""
    )

    val targetFlit = recvQueue(flit.srcX)(flit.srcY).dequeue()
    assert(targetFlit==flit,
      s"""unanticipated flit received
          |received   ${flit.message}
          |but target ${targetFlit.message}""".stripMargin)
  }

  def willRecv(packet:PacketSim): Unit ={
    println(packet.rawFlits.head.message+s" ${packet.rawFlits.length}")
    recvQueue(packet.srcX)(packet.srcY) ++= packet.rawFlits
  }

  def allReceived:Boolean = recvQueue.flatten.forall(q => q.isEmpty)

  def printResideFlits(){
    Seq.tabulate(xSize, ySize){ (srcX, srcY) =>
      val rq = recvQueue(srcX)(srcY)
      if(rq.nonEmpty){
        println(s"node x$x:y$y resides ${rq.length} flits head flits is ${rq.head.message}")
      }
    }
  }
}


class HeaderPackerTest extends AnyFunSuite{

  test("Header Packer test"){
    SimConfig
      .withWave
      .compile(new HeaderPacker(4, 4, 8, 8))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(2)
        StreamReadyRandomizer(dut.io.packed, dut.clockDomain)

        dut.io.raw.fragment #= 0xFF000000L
        dut.io.raw.valid #= true
        dut.clockDomain.waitRisingEdge()
        assert(
          dut.io.headerError.toBoolean,
          "header error didn't trigger by illegal coordinate"
        )

        dut.io.raw.fragment #= 0x00110000L
        dut.io.raw.valid #= true
        dut.clockDomain.waitRisingEdge()
        assert(
          dut.io.headerError.toBoolean,
          "header error didn't trigger by write private field"
        )
        val driver = PacketDriver(dut.io.raw, dut.clockDomain, 2, 2, 4, 4, Local)
        driver.randomGen(32, withHeadPack = false)
        dut.clockDomain.waitRisingEdge(1000)
      }
  }
}

class MeshTest extends AnyFunSuite{
  val (xSize, ySize) = (2, 3)

  test("Header Packer test"){
    SimConfig
      .withWave
      .compile(Mesh(xSize, ySize))
      .doSim(1490725378){dut =>
        dut.clockDomain.forkStimulus(2)
        dut.localClockDomain.forkStimulus(3)
        SimTimeout(10000)

        val monitors = Seq.tabulate(xSize, ySize){(x, y)=>
          PacketRecvMonitor(
            dut.localIfs(x)(y).pop,
            dut.localClockDomain,
            x, y, xSize, ySize
          )
        }
        val drivers = Seq.tabulate(xSize, ySize){(x, y)=>
          PacketDriver(
            dut.localIfs(x)(y).push,
            dut.localClockDomain,
            x, y, xSize, ySize,
            Local
          )
        }
        //for(d <- drivers.flatten){
        //  val packets = d.randomGen(64, withHeadPack = false)
        //  for(p <- packets){
        //    monitors(p.destX)(p.destY) willRecv p
        //  }
        //}
        for(id <- 0 until 2){
          val p = PacketSim(1,0,id>>4,id&0xF,1,false,Local)
          drivers(0)(0).inject(p)
          monitors(1)(0) willRecv p
        }
        for(m <- monitors.flatten){
          dut.localClockDomain.waitRisingEdgeWhere(
            m.allReceived
          )
        }
      }

  }

  test("fuxian"){
    SimConfig
      .withWave
      .compile(Mesh(xSize, ySize))
      .doSim(1490725378){dut =>
        val drivers = Seq.tabulate(xSize, ySize){(x, y)=>
          dut.localIfs(x)(y).pop.ready #= true
          PacketDriver(
            dut.localIfs(x)(y).push,
            dut.localClockDomain,
            x, y, xSize, ySize,
            Local
          )
        }
        val flits = (23 to 0x2b).map{ i=>
          val raw = 0x10000000 & i
          FlitSim(raw, true)
        }
        for(flit <- flits){
          drivers(0)(0).injectFlit(flit)
        }
        dut.localClockDomain.waitRisingEdge(100)
      }
  }
}
