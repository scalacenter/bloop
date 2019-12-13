package bloop.exec

import bloop.cli.CommonOptions
import bloop.data.JdkConfig
import bloop.engine.tasks.RunMode
import bloop.io.{AbsolutePath, Paths}
import bloop.logging.{DebugFilter, Logger}
import bloop.util.CrossPlatform
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.{Attributes, JarOutputStream, Manifest}
import monix.eval.Task
import scala.util.{Failure, Success, Try}

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
      if (skipJargs) (Array.empty[String], args0)
      else args0.partition(_.startsWith("-J"))

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
  def apply(config: JdkConfig, classpath: Array[AbsolutePath]): JvmProcessForker =
    new JvmForker(config, classpath)

  def apply(
      config: JdkConfig,
      classpath: Array[AbsolutePath],
      mode: RunMode
  ): JvmProcessForker = {
    mode match {
      case RunMode.Normal => new JvmForker(config, classpath)
      case RunMode.Debug => new JvmDebuggingForker(new JvmForker(config, classpath))
    }
  }
}

/**
 * @param config The configuration describing how to start the new JVM
 * @param classpath Classpath with which the code should be executed
 */
final class JvmForker(config: JdkConfig, classpath: Array[AbsolutePath]) extends JvmProcessForker {

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

    def logDebugIfVerbose(debugMsg: => String): Task[Unit] =
      if (logger.isVerbose) {
        Task(logger.debug(debugMsg)(DebugFilter.All))
      } else {
        Task.unit
      }

    def runCmd(cmd: List[String]): Task[Int] = {
      def debugOptions =
        s"""
           |Fork options:
           |   command      = '${cmd.mkString(" ")}'
           |   cwd          = '$cwd'""".stripMargin

      logDebugIfVerbose(debugOptions).flatMap(_ => Forker.run(cwd, cmd, logger, opts))
    }

    val jvmOptions = jargs.map(_.stripPrefix("-J")) ++ config.javaOptions
    val fullClasspath = classpath ++ extraClasspath
    val fullClasspathStr = fullClasspath.map(_.syntax).mkString(File.pathSeparator)

    // Windows max cmd line length is 32767, which seems to be the least of the common shells.
    val processCmdCharLimit = 30000

    Task.fromTry(javaExecutable).flatMap { java =>
      val classpathOption = "-cp" :: fullClasspathStr :: Nil
      val appOptions = mainClass :: args.toList
      val cmd = java.syntax :: jvmOptions.toList ::: classpathOption ::: appOptions
      val cmdStr = cmd.mkString(" ")

      // Note that we current only shorten the classpath portion and not other options
      // Thus we do not yet *guarantee* that the command will not exceed OS limits
      if (cmdStr.length > processCmdCharLimit) {
        def charLimitMsg =
          s"""|Supplied command to fork exceeds character limit of $processCmdCharLimit
              |Creating a temporary MANIFEST jar for classpath entries
              |""".stripMargin

        logDebugIfVerbose(charLimitMsg).flatMap { _ =>
          withTempManifestJar(fullClasspath, logger) { manifestJar =>
            val shortClasspathOption = "-cp" :: manifestJar.syntax :: Nil
            val shortCmd = java.syntax :: jvmOptions.toList ::: shortClasspathOption ::: appOptions
            runCmd(shortCmd)
          }
        }
      } else {
        runCmd(cmd)
      }
    }
  }

  private def withTempManifestJar[A](
      classpath: Array[AbsolutePath],
      logger: Logger
  )(op: AbsolutePath => Task[A]): Task[A] = {

    val manifestJar = Files.createTempFile("jvm-forker-manifest", ".jar").toAbsolutePath
    val manifestJarAbs = AbsolutePath(manifestJar)
    def cleanup = {
      Task {
        if (logger.isVerbose) {
          logger.debug(s"Cleaning up temporary MANIFEST jar: $manifestJar")(DebugFilter.All)
        }
        Paths.delete(manifestJarAbs)
      }
    }

    val classpathStr = classpath.map(addTrailingSlashToDirectories).mkString(" ")

    val manifest = new Manifest()
    manifest.getMainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
    manifest.getMainAttributes.put(Attributes.Name.CLASS_PATH, classpathStr)

    val out = Files.newOutputStream(manifestJar)
    // This needs to be declared since jos itself should be set to close as well.
    var jos: JarOutputStream = null
    try {
      jos = new JarOutputStream(out, manifest)
    } finally {
      if (jos == null) {
        out.close()
      } else {
        jos.close()
      }
    }

    op(manifestJarAbs)
      .doOnFinish(_ => cleanup)
      .doOnCancel(cleanup)
  }

  // Turns out manifest files can use absolute path directories in the classpath
  // But they need a trailing slash
  private def addTrailingSlashToDirectories(path: AbsolutePath): String = {
    val syntax = path.syntax
    val separatorAdded = if (syntax.endsWith(".jar")) {
      syntax
    } else {
      syntax + File.separator
    }
    // On Windows, the drive letter is prepended to absolute paths
    // Even though the official Java docs say to do this,
    // it seems this fails for MANIFEST files, so we remove the drive letter
    separatorAdded.substring(separatorAdded.indexOf(":") + 1)
  }

  private def javaExecutable: Try[AbsolutePath] = {
    val javaPath = config.javaHome.resolve("bin").resolve("java")
    if (javaPath.exists) Success(javaPath)
    else {
      val javaExePath = config.javaHome.resolve("bin").resolve("java.exe")
      if (CrossPlatform.isWindows && javaExePath.exists) Success(javaExePath)
      else Failure(new IllegalStateException(s"Missing java executable at $javaPath!"))
    }
  }
}

final class JvmDebuggingForker(underlying: JvmProcessForker) extends JvmProcessForker {

  override def newClassLoader(parent: Option[ClassLoader]): ClassLoader =
    underlying.newClassLoader(parent)

  override def runMain(
      cwd: AbsolutePath,
      mainClass: String,
      args: Array[String],
      jargs0: Array[String],
      logger: Logger,
      opts: CommonOptions,
      extraClasspath: Array[AbsolutePath]
  ): Task[Int] = {
    val jargs = jargs0 :+ enableDebugInterface
    underlying.runMain(cwd, mainClass, args, jargs, logger, opts, extraClasspath)
  }

  private def enableDebugInterface: String = {
    s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,quiet=n"
  }
}
