package bloop.exec

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scala.sys.process.BasicIO
import scala.util.control.NonFatal

import bloop.cli.CommonOptions
import bloop.cli.ExitStatus
import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.task.Task

import monix.execution.Cancelable

object Forker {
  private implicit val logContext: DebugFilter = DebugFilter.All

  /** The code returned after a successful execution. */
  private final val EXIT_OK = 0

  /** The code returned after the execution errored. */
  private final val EXIT_ERROR = 1

  /**
   * Converts this exit code to an `ExitStatus`
   * If execution failed, `RunError` is returned. Otherwise, `Ok`.
   *
   * @param exitCode The exit code to convert
   * @return The corresponding exit status
   */
  def exitStatus(exitCode: Int): ExitStatus = {
    if (exitCode == EXIT_OK) ExitStatus.Ok
    else ExitStatus.RunError
  }

  /**
   * Runs `cmd` in a new process and logs the results. The exit code is returned
   *
   * @param cwd    The directory in which to start the process
   * @param cmd    The command to run
   * @param logger Where to log the messages from execution
   * @param opts   The options to run the program with
   * @return The exit code of the process
   */
  def run(
      cwd: AbsolutePath,
      cmd: Seq[String],
      logger: Logger,
      opts: CommonOptions
  ): Task[Int] = {
    if (cwd.exists) runProcess(cwd, cmd, logger, opts)
    else {
      logger.error(s"Working directory '$cwd' does not exist")
      Task.now(EXIT_ERROR)
    }
  }

  private def runProcess(
      cwd: AbsolutePath,
      cmd: Seq[String],
      logger: Logger,
      opts: CommonOptions
  ): Task[Int] = {
    val consumeInput: Option[Cancelable] = None
    @volatile var shutdownInput: Boolean = false

    /* We need to gobble the input manually with a fixed delay because otherwise
     * the remote process will not see it.
     *
     * The input gobble runs on a 50ms basis and it can process a maximum of 4096
     * bytes at a time. The rest that is not read will be read in the next 50ms. */
    def goobleInput(to: OutputStream): Cancelable = {
      val duration = FiniteDuration(50, TimeUnit.MILLISECONDS)
      ExecutionContext.ioScheduler.scheduleWithFixedDelay(duration, duration) {
        val buffer = new Array[Byte](4096)
        if (shutdownInput) {
          consumeInput.foreach(_.cancel())
        } else {
          try {
            if (opts.in.available() > 0) {
              val read = opts.in.read(buffer, 0, buffer.length)
              if (read == -1) ()
              else {
                to.write(buffer, 0, read)
                to.flush()
              }
            }
          } catch {
            case t: IOException =>
              logger.debug(s"Error from input gobbler: ${t.getMessage}")
              logger.trace(t)
              // Rethrow so that Monix cancels future scheduling of the same task
              throw t
          }
        }
      }
    }

    val runTask = run(
      Some(cwd.underlying.toFile),
      cmd,
      logger,
      opts.env.toMap,
      writeToStdIn = outputStream => {
        val mainCancellable = goobleInput(outputStream)
        Cancelable { () =>
          shutdownInput = true
          mainCancellable.cancel()
        }
      },
      debugLog = msg => {
        opts.ngout.println(msg)
        logger.debug(msg)
      }
    )

    Task {
      logger.debug(s"""Starting forked process:
                      |  cwd = '$cwd'
                      |  cmd = '${cmd.mkString(" ")}'""".stripMargin)
    }.flatMap(_ => runTask)
  }

  def run(
      cwd: Option[java.io.File],
      cmd: Seq[String],
      logger: Logger,
      env: Map[String, String],
      writeToStdIn: OutputStream => Cancelable,
      debugLog: String => Unit
  ): Task[Int] = {

    def cancelTask(
        writeStdIn: Cancelable,
        outReaders: List[Thread],
        ps: Process
    ): Task[Unit] = {
      Task {
        writeStdIn.cancel()
        ps.destroy()

        val normalTermination = ps.waitFor(200, TimeUnit.MILLISECONDS)

        val terminated =
          normalTermination || {
            ps.destroyForcibly()
            ps.waitFor(200, TimeUnit.MILLISECONDS)
          }
        outReaders.foreach(_.interrupt())
        val cmdStr = cmd.mkString(" ")
        if (!terminated) {
          val msg = s"The cancellation could not destroy process '$cmdStr'"
          debugLog(msg)
        } else {
          val msg = s"The run process '${cmdStr}' has been closed"
          debugLog(msg)
        }
      }
    }

    def awaitCompletion(
        writeStdIn: Cancelable,
        outReaders: List[Thread],
        ps: Process
    ): Task[Int] = {
      Task {
        val exitCode = ps.waitFor()
        writeStdIn.cancel()
        outReaders.foreach(_.join())
        logger.debug(s"Forked process exited with code: $exitCode")
        exitCode
      }
    }

    def readOutput(stream: InputStream, f: String => Unit): Thread = {
      val thread = new Thread {
        override def run(): Unit = {
          // use scala.sys.process implementation
          try {
            BasicIO.processFully(f)(stream)
          } catch { case NonFatal(_) => }
        }
      }
      thread.setDaemon(true)
      thread.start()
      thread
    }

    val task = Task {
      val builder = new ProcessBuilder(cmd.asJava)
      cwd.foreach(builder.directory(_))
      val envMap = builder.environment()
      envMap.putAll(env.asJava)
      builder.redirectErrorStream(false)
      builder.start()
    }.flatMap { ps =>
      val writeIn = writeToStdIn(ps.getOutputStream)
      val outReaders =
        List(
          readOutput(ps.getInputStream(), logger.info),
          readOutput(ps.getErrorStream(), logger.error)
        )
      awaitCompletion(writeIn, outReaders, ps)
        .doOnCancel(cancelTask(writeIn, outReaders, ps))
        .onErrorRecover {
          case error =>
            writeIn.cancel()
            outReaders.foreach(_.interrupt())
            logger.error(error.getMessage)
            Forker.EXIT_ERROR
        }
    }

    task.onErrorRecover {
      case e =>
        logger.error(e.getMessage)
        Forker.EXIT_ERROR
    }
  }

}
