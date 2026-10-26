package bloop

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.DurationInt

import bloop.cli.NailgunOutputSession
import bloop.engine.ExecutionContext

import monix.execution.schedulers.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

object NailgunOutputSessionSpec {

  // Emulates the socket end of nailgun's NGOutputStream: every write() call it receives is
  // sent to the client as one protocol chunk followed by a socket flush, so counting
  // write() calls counts chunks.
  final class ChunkCountingOutputStream extends OutputStream {
    val chunks = new AtomicInteger(0)
    private val bytes = new ByteArrayOutputStream()
    override def write(b: Int): Unit = synchronized {
      chunks.incrementAndGet()
      bytes.write(b)
    }
    override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
      chunks.incrementAndGet()
      bytes.write(b, off, len)
    }
    def text: String = synchronized { new String(bytes.toByteArray, StandardCharsets.UTF_8) }
  }

  // Records chunks written to any of its targets in one global sequence, to observe the
  // relative order in which a client would receive out and err chunks.
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

@Category(Array(classOf[bloop.FastTests]))
class NailgunOutputSessionSpec {
  import NailgunOutputSessionSpec.ChunkCountingOutputStream
  import NailgunOutputSessionSpec.ChunkRecorder

  @Test
  def flushesPendingOutputAfterOneFlushPeriod: Unit = {
    val scheduler = TestScheduler()
    val outCounter = new ChunkCountingOutputStream
    val errCounter = new ChunkCountingOutputStream
    val session = NailgunOutputSession.start(outCounter, errCounter, scheduler)
    try {
      // Shorter than the buffer and never explicitly flushed: only the periodic
      // background flush can deliver it, keeping interactive output timely.
      session.out.print("prompt> ")
      assertEquals("Output must stay buffered until the flush period elapses", "", outCounter.text)
      scheduler.tick(100.millis)
      assertEquals(
        "Output must be delivered by the first periodic flush",
        "prompt> ",
        outCounter.text
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

  @Test
  def dropsOutputWrittenAfterFinish: Unit = {
    val outCounter = new ChunkCountingOutputStream
    val errCounter = new ChunkCountingOutputStream
    val session = NailgunOutputSession.start(outCounter, errCounter, ExecutionContext.ioScheduler)
    session.out.print("tail")
    session.finish()
    assertEquals("Finish must drain pending output", "tail", outCounter.text)
    // The exit chunk follows `finish()`, so anything accepted later could reach the
    // client after its session ended: late writes must be dropped, even flushed ones.
    session.out.print("late")
    session.out.flush()
    assertEquals("Output written after finish must be dropped", "tail", outCounter.text)
  }

  @Test
  def batchesTheOutputOfOneStream: Unit = {
    val outCounter = new ChunkCountingOutputStream
    val errCounter = new ChunkCountingOutputStream
    // A scheduler that never ticks, so only the buffer size decides when to drain.
    val session = NailgunOutputSession.start(outCounter, errCounter, TestScheduler())
    val lines = 100000
    try {
      var i = 0
      while (i < lines) {
        session.out.print("line-" + i + "\n")
        i += 1
      }
    } finally session.finish()

    val received = outCounter.text.linesIterator.toVector
    assertEquals("Every line must reach the client", lines, received.size)
    val firstWrongLine = received.indices.find(i => received(i) != "line-" + i)
    assertEquals("Lines must reach the client in order", None, firstWrongLine)
    // About 1 MB of output: one chunk per full buffer instead of one per line.
    val chunks = outCounter.chunks.get
    assertTrue(s"$lines lines reached the client as $chunks chunks", chunks < 100)
  }
}
