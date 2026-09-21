package bloop.cli

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit

import com.martiansoftware.nailgun.NGContext
import monix.execution.Cancelable
import monix.execution.Scheduler

/**
 * Owns the output streams of one nailgun session and batches their writes.
 * Every write reaching a nailgun stream becomes one protocol chunk plus a
 * socket flush, which large run outputs turn into a connection-killing flood.
 *
 * Pending output is kept as one ordered sequence of per-stream segments.
 * Within a stream, write order is always preserved. The relative order of
 * `out` and `err` writes is preserved up to a bounded number of segments per
 * drain batch; past that bound — streams alternating faster than drains —
 * writes coalesce into the batch's segment for their stream, trading exact
 * cross-stream interleaving for a bounded chunk count. A drain happens when
 * pending output exceeds the buffer size, every `flushPeriodMillis` (keeping
 * interactive output timely), on explicit `flush()`, and on `finish()`.
 */
private[bloop] final class NailgunOutputSession private (
    ngOut: OutputStream,
    ngErr: OutputStream,
    scheduler: Scheduler,
    flushPeriodMillis: Long
) {
  import NailgunOutputSession.BufferSize
  import NailgunOutputSession.MaxSegmentsPerBatch
  import NailgunOutputSession.Segment

  private val stateLock = new Object
  private val drainLock = new Object
  private var finished = false
  private var pending = List.empty[Segment] // in reverse write order
  private var pendingBytes = 0
  private var segmentsInBatch = 0

  val out: PrintStream = stream(ngOut)
  val err: PrintStream = stream(ngErr)

  private val flusher: Cancelable = scheduler.scheduleWithFixedDelay(
    flushPeriodMillis,
    flushPeriodMillis,
    TimeUnit.MILLISECONDS,
    new Runnable { def run(): Unit = drain() }
  )

  private def stream(target: OutputStream): PrintStream =
    new PrintStream(
      new OutputStream {
        override def write(b: Int): Unit = write(Array(b.toByte), 0, 1)
        override def write(b: Array[Byte], off: Int, len: Int): Unit = append(target, b, off, len)
        override def flush(): Unit = drain()
      },
      false
    )

  private def append(target: OutputStream, b: Array[Byte], off: Int, len: Int): Unit = {
    val drainNow = stateLock.synchronized {
      if (finished) false
      else {
        val segment = pending match {
          case head :: _ if head.target eq target => head
          case rest =>
            // Bounded cross-stream ordering: every segment becomes one chunk, so
            // streams alternating faster than drains could recreate the chunk
            // flood. Past the cap, merge into this batch's segment for the
            // stream, trading exact interleaving for a bounded chunk count.
            val recycled =
              if (segmentsInBatch >= MaxSegmentsPerBatch) rest.find(_.target eq target)
              else None
            recycled.getOrElse {
              val fresh = new Segment(target)
              pending = fresh :: rest
              segmentsInBatch += 1
              fresh
            }
        }
        segment.bytes.write(b, off, len)
        pendingBytes += len
        pendingBytes >= BufferSize
      }
    }
    if (drainNow) drain()
  }

  // Snapshotting under `drainLock` linearizes concurrent drains: each batch is
  // written out completely before the next snapshot is taken.
  private def drain(): Unit = drainLock.synchronized {
    val toWrite = stateLock.synchronized {
      val segments = pending.reverse
      pending = Nil
      pendingBytes = 0
      segmentsInBatch = 0
      segments
    }
    try
      toWrite.foreach { segment =>
        segment.bytes.writeTo(segment.target) // one write = one nailgun chunk
        segment.target.flush()
      }
    catch {
      // The session socket is gone. Stop buffering for it so that later writes are
      // dropped instead of failing this drain again once per batch.
      case _: IOException =>
        markFinished()
        ()
    }
  }

  /** Stops the session, returning true if this call was the one that stopped it. */
  private def markFinished(): Boolean = stateLock.synchronized {
    val was = finished
    finished = true
    !was
  }

  /** Stops batching and drains pending output; later writes are dropped. */
  def finish(): Unit = {
    // Always cancel: `drain()` can have finished the session without stopping the timer.
    flusher.cancel()
    if (markFinished()) drain()
  }
}

private[bloop] object NailgunOutputSession {
  private final val BufferSize = 16 * 1024
  private final val DefaultFlushPeriodMillis = 100L
  private final val MaxSegmentsPerBatch = 64

  private final class Segment(val target: OutputStream) {
    val bytes = new ByteArrayOutputStream()
  }

  def start(
      ngOut: OutputStream,
      ngErr: OutputStream,
      scheduler: Scheduler
  ): NailgunOutputSession =
    new NailgunOutputSession(ngOut, ngErr, scheduler, DefaultFlushPeriodMillis)

  /**
   * Runs one nailgun command with a batched output session: `body` computes the
   * exit code, pending output is drained, and only then is the exit chunk sent,
   * because it ends the session on the client as soon as it arrives.
   */
  def runWithSession(ngContext: NGContext, scheduler: Scheduler)(
      body: NailgunOutputSession => Int
  ): Unit = {
    val session = start(ngContext.out, ngContext.err, scheduler)
    val exitCode =
      try body(session)
      finally session.finish()
    ngContext.exit(exitCode)
  }
}
