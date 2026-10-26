package bloop.cli

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit

import com.martiansoftware.nailgun.NGContext
import monix.execution.Cancelable
import monix.execution.Scheduler

/**
 * Batches the output of one nailgun session. Nailgun sends every write as one chunk
 * followed by a socket flush, so a program that prints line by line costs a chunk per
 * line. Pending output belongs to one stream at a time and is drained before the other
 * stream writes, so `out` and `err` reach the client in the order they were written.
 */
private[bloop] final class NailgunOutputSession private (
    ngOut: OutputStream,
    ngErr: OutputStream,
    scheduler: Scheduler
) {
  import NailgunOutputSession.BufferSize
  import NailgunOutputSession.FlushPeriodMillis

  private val pending = new ByteArrayOutputStream()
  private var pendingTarget = ngOut
  private var finished = false

  val out: PrintStream = stream(ngOut)
  val err: PrintStream = stream(ngErr)

  // Delivers output that stays below the buffer size, such as a prompt.
  private val flusher: Cancelable = scheduler.scheduleWithFixedDelay(
    FlushPeriodMillis,
    FlushPeriodMillis,
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

  private def append(target: OutputStream, b: Array[Byte], off: Int, len: Int): Unit =
    synchronized {
      if (!finished) {
        if (target ne pendingTarget) drainPending()
        pendingTarget = target
        pending.write(b, off, len)
        if (pending.size >= BufferSize) drainPending()
      }
    }

  private def drain(): Unit = synchronized(drainPending())

  // One write to a nailgun stream is one chunk.
  private def drainPending(): Unit =
    if (pending.size > 0) {
      pending.writeTo(pendingTarget)
      pendingTarget.flush()
      pending.reset()
    }

  /** Drains pending output and drops later writes: the exit chunk ends the session. */
  def finish(): Unit = {
    flusher.cancel()
    synchronized {
      finished = true
      drainPending()
    }
  }
}

private[bloop] object NailgunOutputSession {
  private final val BufferSize = 16 * 1024
  private final val FlushPeriodMillis = 100L

  def start(
      ngOut: OutputStream,
      ngErr: OutputStream,
      scheduler: Scheduler
  ): NailgunOutputSession =
    new NailgunOutputSession(ngOut, ngErr, scheduler)

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
