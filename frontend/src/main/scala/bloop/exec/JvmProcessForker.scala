package bloop.exec

import java.io.File.pathSeparator
import java.net.{InetSocketAddress, ServerSocket, URLClassLoader}

import bloop.cli.CommonOptions
import bloop.dap.{DebugSession, DebugSessionLogger}
import bloop.io.AbsolutePath
import bloop.engine.tasks.RunMode
import bloop.logging.{DebugFilter, Logger}
import monix.eval.Task

/**
 * Collects configuration to start a new program in a new process
 *
 * The name comes from a similar utility https://github.com/sshtools/forker.
 */
trait JvmProcessForker {

  /**
   * Creates a `ClassLoader` from the classpath of this `ForkProcess`
   *
   * @param parent A parent classloader
   * @return A classloader constructed from the classpath of this `ForkProcess`
   */
  def newClassLoader(parent: Option[ClassLoader]): ClassLoader

  /**
   * Run the main function in class `className`, passing it `args`
   *
   * @param cwd            The directory in which to start the forked JVM
   * @param mainClass      The fully qualified name of the class to run
   * @param args0          The arguments to pass to the main method. If they contain args
   *                       starting with `-J`, they will be interpreted as jvm options.
   * @param skipJargs      Skip the interpretation of `-J` options in `args`.
   * @param logger         Where to log the messages from execution
   * @param opts           The options to run the program with
   * @param extraClasspath Paths to append to the classpath before running
   * @return 0 if the execution exited successfully, a non-zero number otherwise
   *
   *
   */
  final def runMain(
      cwd: AbsolutePath,
      mainClass: String,
      args0: Array[String],
      skipJargs: Boolean,
      logger: Logger,
      opts: CommonOptions,
      extraClasspath: Array[AbsolutePath] = Array.empty
  ): Task[Int] = {
    val (userJvmOptions, userArgs) =
      if (skipJargs) (Array.empty[String], args0) else args0.partition(_.startsWith("-J"))

    runMain(cwd, mainClass, userArgs, userJvmOptions, logger, opts, extraClasspath)
  }

  def runMain(
      cwd: AbsolutePath,
      mainClass: String,
      args: Array[String],
      jargs: Array[String],
      logger: Logger,
      opts: CommonOptions,
      extraClasspath: Array[AbsolutePath]
  ): Task[Int]
}

object JvmProcessForker {
  def apply(javaEnv: JavaEnv, classpath: Array[AbsolutePath]): JvmProcessForker =
    new JvmForker(javaEnv, classpath)

  def apply(
      javaEnv: JavaEnv,
      classpath: Array[AbsolutePath],
      mode: RunMode
  ): JvmProcessForker = {
    mode match {
      case RunMode.Normal => new JvmForker(javaEnv, classpath)
      case RunMode.Debug => new JvmDebuggingForker(new JvmForker(javaEnv, classpath))
    }
  }
}

/**
 * @param env   The configuration describing how to start the new JVM
 * @param classpath Classpath with which the code should be executed
 */
final class JvmForker(env: JavaEnv, classpath: Array[AbsolutePath]) extends JvmProcessForker {

  /**
   * Creates a `ClassLoader` from the classpath of this `ForkProcess`
   *
   * @param parent A parent classloader
   * @return A classloader constructed from the classpath of this `ForkProcess`
   */
  override def newClassLoader(parent: Option[ClassLoader]): ClassLoader = {
    val classpathEntries = classpath.map(_.underlying.toUri.toURL)
    new URLClassLoader(classpathEntries, parent.orNull)
  }

  override def runMain(
      cwd: AbsolutePath,
      mainClass: String,
      args: Array[String],
      jargs: Array[String],
      logger: Logger,
      opts: CommonOptions,
      extraClasspath: Array[AbsolutePath]
  ): Task[Int] = {
    val jvmOptions = jargs ++ env.javaOptions
    val fullClasspath = (classpath ++ extraClasspath).map(_.syntax).mkString(pathSeparator)
    val java = env.javaHome.resolve("bin").resolve("java")
    val classpathOption = "-cp" :: fullClasspath :: Nil
    val appOptions = mainClass :: args.toList
    val cmd = java.syntax :: jvmOptions.toList ::: classpathOption ::: appOptions

    val logTask =
      if (logger.isVerbose) {
        val debugOptions =
          s"""
             |Fork options:
             |   command      = '${cmd.mkString(" ")}'
             |   cwd          = '$cwd'""".stripMargin
        Task(logger.debug(debugOptions)(DebugFilter.All))
      } else Task.unit
    logTask.flatMap(_ => Forker.run(cwd, cmd, logger, opts))
  }
}

final class JvmDebuggingForker(underlying: JvmProcessForker) extends JvmProcessForker {

  override def newClassLoader(parent: Option[ClassLoader]): ClassLoader =
    underlying.newClassLoader(parent)

  override def runMain(
      cwd: AbsolutePath,
      mainClass: String,
      args: Array[String],
      jArgs: Array[String],
      logger: Logger,
      opts: CommonOptions,
      extraClasspath: Array[AbsolutePath]
  ): Task[Int] = {
    val address = findFreeSocketAddress
    val jvmOptions = jArgs :+ jdiOpt(address.getHostName, address.getPort)
    underlying.runMain(cwd, mainClass, args, jvmOptions, logger, opts, extraClasspath)
  }

  private def findFreeSocketAddress: InetSocketAddress = {
    val server = new ServerSocket(0)
    server.close()
    server.getLocalSocketAddress
    new InetSocketAddress(server.getInetAddress, server.getLocalPort)
  }

  private def jdiOpt(host: String, port: Int): String = {
    s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,quiet=n,address=$host:$port"
  }
}
