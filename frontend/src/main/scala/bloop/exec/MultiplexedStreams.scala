package bloop.exec

import bloop.logging.{Logger, ProcessLogger}

import java.util.concurrent.{ExecutionException, Executors}

import com.martiansoftware.nailgun.ThreadLocalPrintStream

/**
 * Helper object to redirect bytes written to `System.out` and `System.err` to a given logger.
 */
object MultiplexedStreams {
  private lazy val multiplexedOut = System.out.asInstanceOf[ThreadLocalPrintStream]
  private lazy val multiplexedErr = System.err.asInstanceOf[ThreadLocalPrintStream]

  /** Executor that runs code while managing the streams */
  private val executor = Executors.newCachedThreadPool()

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
    initialize()
    var result: Option[T] = None
    val thread = new Thread {
      override def run(): Unit = {
        registerStreams(logger)
        result = {
          try Some(op)
          finally flushStreams()
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
   * Registers the redirection of output from this thread to `logger`.
   *
   * @param logger The logger that will receive the output produced by the current `thread`.
   */
  private def registerStreams(logger: Logger): Unit = {
    val out = ProcessLogger.toPrintStream(logger.info)
    val err = ProcessLogger.toPrintStream(logger.error)
    multiplexedOut.init(out)
    multiplexedErr.init(err)
  }

  /**
   * Flushes the two streams that receive output for the current thread.
   */
  private def flushStreams(): Unit = {
    System.out.flush()
    System.err.flush()
  }

  /**
   * Initialize `System.out` and `System.err` to `ThreadLocalPrintStream`s,
   * if they're something else. These streams will default to the current streams.
   */
  private def initialize(): Unit = {
    System.out match {
      case _: ThreadLocalPrintStream => ()
      case _ => System.setOut(new ThreadLocalPrintStream(System.out))
    }
    System.err match {
      case _: ThreadLocalPrintStream => ()
      case _ => System.setErr(new ThreadLocalPrintStream(System.err))
    }
  }
}
