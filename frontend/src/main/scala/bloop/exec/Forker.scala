package bloop.exec

import java.io.File.pathSeparator
import java.nio.file.Files
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import bloop.cli.{CommonOptions, ExitStatus}
import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import bloop.logging.{LogContext, Logger}
import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess}
import monix.eval.Task
import monix.execution.Cancelable

import scala.concurrent.duration.FiniteDuration

/**
 * Collects configuration to start a new program in a new process
 *
 * The name comes from a similar utility https://github.com/sshtools/forker.
 *
 * @param javaEnv   The configuration describing how to start the new JVM
 * @param classpath The full classpath with which the code should be executed
 */
final case class Forker(javaEnv: JavaEnv, classpath: Array[AbsolutePath]) {

  /**
   * Creates a `ClassLoader` from the classpath of this `ForkProcess`
   *
   * @param parent A parent classloader
   * @return A classloader constructed from the classpath of this `ForkProcess`
   */
  def newClassLoader(parent: Option[ClassLoader]): ClassLoader = {
    val classpathEntries = classpath.map(_.underlying.toUri.toURL)
    new URLClassLoader(classpathEntries, parent.orNull)
  }

  /**
   * Run the main function in class `className`, passing it `args`
   *
   * @param cwd            The directory in which to start the forked JVM
   * @param mainClass      The fully qualified name of the class to run
   * @param args           The arguments to pass to the main method
   * @param logger         Where to log the messages from execution
   * @param opts           The options to run the program with
   * @param extraClasspath Paths to append to the classpath before running
   * @return 0 if the execution exited successfully, a non-zero number otherwise
   */
  def runMain(
      cwd: AbsolutePath,
      mainClass: String,
      args: Array[String],
      logger: Logger,
      opts: CommonOptions,
      extraClasspath: Array[AbsolutePath] = Array.empty
  ): Task[Int] = {
    val fullClasspath = (classpath ++ extraClasspath).map(_.syntax).mkString(pathSeparator)
    val java = javaEnv.javaHome.resolve("bin").resolve("java")
    val classpathOption = "-cp" :: fullClasspath :: Nil
    val appOptions = mainClass :: args.toList
    val cmd = java.syntax :: javaEnv.javaOptions.toList ::: classpathOption ::: appOptions
    val logTask =
      if (logger.isVerbose) {
        val debugOptions =
          s"""
             |Fork options:
             |   command      = '${cmd.mkString(" ")}'
             |   cwd          = '$cwd'
             |   classpath    = '$fullClasspath'
             |   java_home    = '${javaEnv.javaHome}'
             |   java_options = '${javaEnv.javaOptions.mkString(" ")}""".stripMargin
        Task(logger.debugInContext(debugOptions)(LogContext.All))
      } else Task.unit
    logTask.flatMap(_ => Forker.run(cwd, cmd, logger, opts))
  }

}

object Forker {

  private implicit val logContext: LogContext = LogContext.All

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
  def run(cwd: AbsolutePath, cmd: Seq[String], logger: Logger, opts: CommonOptions): Task[Int] = {
    import scala.collection.JavaConverters.{propertiesAsScalaMap, mapAsJavaMapConverter}
    if (!Files.exists(cwd.underlying)) {
      Task {
        logger.error(s"Could not start the process because '$cwd' does not exist")
        Forker.EXIT_ERROR
      }
    } else {
      var gobbleInput: Cancelable = null
      final class ProcessHandler extends NuAbstractProcessHandler {
        override def onStart(nuProcess: NuProcess): Unit = {
          logger.debugInContext(
            s"""Starting forked process:
               |  cwd = '$cwd'
               |  pid = '${nuProcess.getPID}'
               |  cmd = '${cmd.mkString(" ")}'""".stripMargin)
        }

        override def onExit(statusCode: Int): Unit =
          logger.debugInContext(s"Forked process exited with code: $statusCode")

        val outBuilder = StringBuilder.newBuilder
        override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
          if (closed) {
            // Make sure that the gobbler never stays awake!
            if (gobbleInput != null) gobbleInput.cancel()
            logger.debugInContext("The process is closed. Emptying buffer...")
            val remaining = outBuilder.mkString
            if (!remaining.isEmpty)
              logger.info(remaining)
          } else {
            Forker.linesFrom(buffer, outBuilder).foreach(logger.info(_))
          }
        }

        val errBuilder = StringBuilder.newBuilder
        override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = {
          if (closed) {
            val remaining = errBuilder.mkString
            if (!remaining.isEmpty)
              logger.error(remaining)
          } else {
            Forker.linesFrom(buffer, errBuilder).foreach(logger.error(_))
          }
        }
      }

      @volatile var shutdownInput: Boolean = false
      Task {
        import com.zaxxer.nuprocess.NuProcessBuilder
        val handler = new ProcessHandler()
        val builder = new NuProcessBuilder(handler, cmd: _*)
        builder.setCwd(cwd.underlying)
        val npEnv = builder.environment()
        npEnv.clear()
        npEnv.putAll(propertiesAsScalaMap(opts.env).asJava)
        builder.start()
      }.flatMap { process =>
        /* We need to gobble the input manually with a fixed delay because otherwise
         * the remote process will not see it. Instead of using the `wantWrite` API
         * we write directly to the process to avoid the extra level of indirection.
         *
         * The input gobble runs on a 50ms basis and it can process a maximum of 4096
         * bytes at a time. The rest that is not read will be read in the next 50ms. */
        val duration = FiniteDuration(50, TimeUnit.MILLISECONDS)
        gobbleInput = ExecutionContext.ioScheduler.scheduleWithFixedDelay(duration, duration) {
          val buffer = new Array[Byte](4096)
          val read = opts.in.read(buffer, 0, buffer.length)
          if (read == -1 || shutdownInput || !process.isRunning) ()
          else process.writeStdin(ByteBuffer.wrap(buffer))
        }

        Task {
          try {
            val exitCode = process.waitFor(0, _root_.java.util.concurrent.TimeUnit.SECONDS)
            logger.debugInContext(s"Process ${process.getPID} exited with code: $exitCode")
            exitCode
          } finally {
            shutdownInput = true
            gobbleInput.cancel()
          }
        }.doOnCancel(Task {
          shutdownInput = true
          gobbleInput.cancel()
          try process.closeStdin(true)
          finally {
            process.destroy(true)
            process.waitFor(200, _root_.java.util.concurrent.TimeUnit.MILLISECONDS)
            if (process.isRunning) {
              val msg = s"The cancellation could not destroy process ${process.getPID}"
              opts.ngout.println(msg)
              logger.debugInContext(msg)
            } else {
              val msg = s"The run process ${process.getPID} has been closed"
              opts.ngout.println(msg)
              logger.debugInContext(msg)
            }
          }
        })
      }
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
