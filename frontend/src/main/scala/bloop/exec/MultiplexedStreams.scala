package bloop.exec

import bloop.logging.{Logger, ProcessLogger}

import java.io.{OutputStream, PrintStream}
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, ExecutionException, Executors}

/**
 * Helper object to redirect bytes written to `System.out` and `System.err` to a given logger.
 */
object MultiplexedStreams {

  /** A combination (stdout, stderr) */
  private case class Streams(out: PrintStream, err: PrintStream)

  /** All the managed streams at this time */
  private val streams = new ConcurrentHashMap[UUID, Streams]

  /**
   * Current (stdout, stderr) when this object is created.
   *
   * These streams will receive output that cannot be redirected somewhere else.
   */
  private val defaultStreams = Streams(System.out, System.err)

  /** Executor that runs code while managing the streams */
  private val executor = Executors.newCachedThreadPool()

  /** The multiplexed `stdout`. */
  val stdout: PrintStream = {
    val stream: OutputStream = (b: Int) => getStreams().out.write(b)
    new PrintStream(stream)
  }

  /** The multiplexed `stderr`. */
  val stderr: PrintStream = {
    val stream: OutputStream = (b: Int) => getStreams().err.write(b)
    new PrintStream(stream)
  }

  initialize()

  /**
   * Run `op` while redirecting `System.out` and `System.err` to `logger`.
   *
   * @param logger Logger that receives bytes written to the standard streams.
   *               All bytes written to `System.out` will be passed to `logger.info`,
   *               while bytes written to `System.err` are passed to `logger.error`.
   * @param op     The operations to execute.
   * @return The result of `op` wrapped in an `Some`, or None if an error occurred.
   */
  def withLoggerAsStreams[T](logger: Logger)(op: => T): Option[T] = {
    var result: Option[T] = None
    val thread = new IdentifiedThread {
      override def work(): Unit = {
        registerStreams(id, logger)
        result = {
          try Some(op)
          finally {
            flushStreams()
            removeStreams(id)
          }
        }
      }
    }

    val future = executor.submit(thread)
    try future.get()
    catch {
      case ex: ExecutionException =>
        throw ex.getCause
    }
    result
  }

  /**
   * Performs initialization. Replaces `System.out` and `System.err`
   * with the multiplexed streams.
   */
  private def initialize(): Unit = {
    System.setOut(stdout)
    System.setErr(stderr)
  }

  /**
   * Registers the redirection of output from thread `id` to `logger`.
   *
   * @param id     The ID of the `Thread` whose output shall be redirected.
   * @param logger The logger that will receive the output produced by the given `thread`.
   */
  private def registerStreams(id: UUID, logger: Logger): Unit = {
    val out = ProcessLogger.toPrintStream(logger.info)
    val err = ProcessLogger.toPrintStream(logger.error)
    val _ = streams.put(id, Streams(out, err))
  }

  /**
   * Un-register the redirection of output from thread `id`.
   *
   * @param id The ID of the thread whose output shall no longer be redirected.
   */
  private def removeStreams(id: UUID): Unit = {
    val _ = streams.remove(id)
  }

  /**
   * Retrieves the underlying `Streams` that should receive the output for the current thread.
   *
   * @return A `Streams` instance that should receive all produced output for this thread.
   */
  private def getStreams(): Streams = {
    Option(IdentifiedThread.id.get)
      .flatMap { id =>
        Option(streams.get(id))
      }
      .getOrElse { defaultStreams }
  }

  /**
   * Flushes the two streams that receive output for the current thread.
   */
  private def flushStreams(): Unit = {
    val Streams(out, err) = getStreams()
    out.flush()
    err.flush()
  }

}
