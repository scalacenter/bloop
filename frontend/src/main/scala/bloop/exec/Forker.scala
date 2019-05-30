package bloop.exec

import java.io.{FileNotFoundException, IOException}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import bloop.cli.{CommonOptions, ExitStatus}
import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import bloop.logging.{DebugFilter, Logger}

import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess, NuProcessBuilder}

import monix.eval.Task
import monix.execution.Cancelable

import scala.concurrent.duration.FiniteDuration

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
    var consumeInput: Cancelable = null
    @volatile var shutdownInput: Boolean = false

    final class ProcessHandler extends NuAbstractProcessHandler {
      private val outBuilder = StringBuilder.newBuilder
      private val errBuilder = StringBuilder.newBuilder

      override def onStart(nuProcess: NuProcess): Unit = {
        logger.debug(s"""Starting forked process:
                        |  cwd = '$cwd'
                        |  pid = '${nuProcess.getPID}'
                        |  cmd = '${cmd.mkString(" ")}'""".stripMargin)
      }

      override def onExit(statusCode: Int): Unit =
        logger.debug(s"Forked process exited with code: $statusCode")

      override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
        if (closed) {
          // Make sure that the gobbler never stays awake!
          if (consumeInput != null) consumeInput.cancel()
          logger.debug("The process is closed. Emptying buffer...")
          val remaining = outBuilder.mkString
          if (!remaining.isEmpty)
            logger.info(remaining)
        } else {
          Forker.linesFrom(buffer, outBuilder).foreach(logger.info)
        }
      }

      override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = {
        if (closed) {
          val remaining = errBuilder.mkString
          if (!remaining.isEmpty)
            logger.error(remaining)
        } else {
          Forker.linesFrom(buffer, errBuilder).foreach(logger.error)
        }
      }
    }

    /* We need to gobble the input manually with a fixed delay because otherwise
     * the remote process will not see it. Instead of using the `wantWrite` API
     * we write directly to the process to avoid the extra level of indirection.
     *
     * The input gobble runs on a 50ms basis and it can process a maximum of 4096
     * bytes at a time. The rest that is not read will be read in the next 50ms. */
    def gobbleInput(process: NuProcess): Task[Int] = {
      val duration = FiniteDuration(50, TimeUnit.MILLISECONDS)
      consumeInput = ExecutionContext.ioScheduler.scheduleWithFixedDelay(duration, duration) {
        val buffer = new Array[Byte](4096)
        if (shutdownInput) {
          if (consumeInput != null) consumeInput.cancel()
        } else {
          try {
            if (opts.in.available() > 0) {
              val read = opts.in.read(buffer, 0, buffer.length)
              if (read == -1 || !process.isRunning) ()
              else process.writeStdin(ByteBuffer.wrap(buffer))
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

      Task {
        try {
          val exitCode = process.waitFor(0, _root_.java.util.concurrent.TimeUnit.SECONDS)
          logger.debug(s"Process ${process.getPID} exited with code: $exitCode")
          exitCode
        } finally {
          shutdownInput = true
          consumeInput.cancel()
        }
      }.doOnCancel(Task {
        shutdownInput = true
        consumeInput.cancel()
        try process.closeStdin(true)
        finally {
          process.destroy(false)
          process.waitFor(400, _root_.java.util.concurrent.TimeUnit.MILLISECONDS)
          process.destroy(true)
          if (process.isRunning) {
            val msg = s"The cancellation could not destroy process ${process.getPID}"
            opts.ngout.println(msg)
            logger.debug(msg)
          } else {
            val msg = s"The run process ${process.getPID} has been closed"
            opts.ngout.println(msg)
            logger.debug(msg)
          }
        }
      })
    }

    run(cwd, cmd, new ProcessHandler(), opts.env.toMap)
      .flatMap(gobbleInput)
      .onErrorRecover {
        case error =>
          logger.error(error.getMessage)
          Forker.EXIT_ERROR
      }
  }

  /**
   * Runs `cmd` in a new process and logs the results. The exit code is returned
   *
   * @param cwd    The directory in which to start the process
   * @param cmd    The command to run
   * @param env   The options to run the program with
   * @return The exit code of the process
   */
  def run(
      cwd: AbsolutePath,
      cmd: Seq[String],
      handler: NuAbstractProcessHandler,
      env: Map[String, String]
  ): Task[NuProcess] = {
    import scala.collection.JavaConverters._
    if (cwd.exists) {
      val builder = new NuProcessBuilder(cmd.asJava, env.asJava)
      builder.setProcessListener(handler)
      builder.setCwd(cwd.underlying)
      println("Starting process")
      Task(builder.start())
    } else {
      val message = s"Working directory '$cwd' does not exist"
      Task.raiseError(new FileNotFoundException(message))
    }
  }

  /**
   * Return an array of lines from a process buffer and a no lines buffer
   *
   * The no lines buffer keeps track of previous messages that did not contain
   * a new line, it is therefore mutated. The buffer is the logs that we just
   * received from our process.
   *
   * This method returns an array of new lines when the messages contain new
   * lines at the end. If there are several new lines in a message but the last
   * one doesn't, then we add the remaining to the string builder.
   *
   * @param buffer The buffer that we receive from NuProcess
   * @param remaining The string builder bookkeeping remaining messages without new lines
   * @return An array of new lines. It can be empty.
   */
  private[bloop] def linesFrom(buffer: ByteBuffer, remaining: StringBuilder): Array[String] = {
    val bytes = new Array[Byte](buffer.remaining())
    buffer.get(bytes)
    val msg = new String(bytes, StandardCharsets.UTF_8)
    // TODO what when we attach to a process on different system?
    val newLines = msg.split(System.lineSeparator, Integer.MAX_VALUE)
    newLines match {
      case Array() => remaining.++=(msg); Array.empty[String]
      case msgs =>
        val msgAtTheEnd = newLines.apply(newLines.length - 1)
        val shouldBuffer = !msgAtTheEnd.isEmpty
        if (shouldBuffer)
          remaining.++=(msgAtTheEnd)

        if (msgs.length > 1) {
          if (shouldBuffer) newLines.init
          else {
            val firstLine = newLines.apply(0)
            newLines(0) = remaining.mkString ++ firstLine
            remaining.clear()
            newLines.init
          }
        } else Array.empty[String]
    }
  }
}
