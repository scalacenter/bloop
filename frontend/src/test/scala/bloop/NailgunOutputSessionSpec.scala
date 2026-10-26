package bloop

import java.io.OutputStream
import java.nio.charset.StandardCharsets

import scala.concurrent.duration.DurationInt

import bloop.cli.NailgunOutputSession
import bloop.engine.ExecutionContext

import monix.execution.schedulers.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

object NailgunOutputSessionSpec {

  // Records every write to any of its targets as one chunk, in one global sequence, the
  // way nailgun's NGOutputStream sends each write. This shows what a client receives and
  // in which order.
  final class ChunkRecorder {
    private val events = new java.util.concurrent.CopyOnWriteArrayList[String]()
    def target(label: String): OutputStream = new OutputStream {
      override def write(b: Int): Unit = write(Array(b.toByte), 0, 1)
      override def write(b: Array[Byte], off: Int, len: Int): Unit = {
        events.add(label + ":" + new String(b, off, len, StandardCharsets.UTF_8))
        ()
      }
    }
    def chunks: List[String] = {
      import scala.collection.JavaConverters._
      events.asScala.toList
    }
  }
}

// LargeRunOutputSpec covers a large output end to end. These cases cover what it cannot
// see: output below the buffer size, and the order of out and err.
@Category(Array(classOf[bloop.FastTests]))
class NailgunOutputSessionSpec {
  import NailgunOutputSessionSpec.ChunkRecorder

  @Test
  def flushesPendingOutputAfterOneFlushPeriod: Unit = {
    val scheduler = TestScheduler()
    val recorder = new ChunkRecorder
    val session =
      NailgunOutputSession.start(recorder.target("out"), recorder.target("err"), scheduler)
    try {
      // Shorter than the buffer and never explicitly flushed: only the periodic
      // background flush can deliver it, keeping interactive output timely.
      session.out.print("prompt> ")
      assertEquals("Output must stay buffered until the flush period elapses", Nil, recorder.chunks)
      scheduler.tick(100.millis)
      assertEquals(
        "Output must be delivered by the first periodic flush",
        List("out:prompt> "),
        recorder.chunks
      )
    } finally session.finish()
  }

  @Test
  def preservesInterleavedOutputOrderAcrossStreams: Unit = {
    val recorder = new ChunkRecorder
    val session =
      NailgunOutputSession.start(
        recorder.target("out"),
        recorder.target("err"),
        ExecutionContext.ioScheduler
      )
    try {
      session.out.print("a")
      session.err.print("b")
      session.out.print("c")
      session.out.flush()
      assertEquals(List("out:a", "err:b", "out:c"), recorder.chunks)
    } finally session.finish()
  }
}
