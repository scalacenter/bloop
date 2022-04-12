package bloop.bloopgun.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.{util => ju}

import scala.util.control.NonFatal

import bloop.bloopgun.core.AvailableAtPath
import bloop.bloopgun.core.LocatedServer

import snailgun.logging.Logger

object Environment {
  final val isWindows: Boolean = scala.util.Properties.isWin
  final val isCygwin: Boolean = {
    Option(System.getenv("OSTYPE")) match {
      case Some(x) => x.toLowerCase(ju.Locale.ENGLISH).contains("cygwin")
      case _ => false
    }
  }

  def cwd: Path = Paths.get(System.getProperty("user.dir"))
  def homeDirectory: Path = Paths.get(System.getProperty("user.home"))
  def defaultBloopDirectory: Path = homeDirectory.resolve(".bloop")
  def bloopGlobalSettingsPath: Path = defaultBloopDirectory.resolve("bloop.json")

  def bloopGlobalSettings(logger: Logger): Either[String, GlobalSettings] = {
    if (Files.isReadable(bloopGlobalSettingsPath)) {
      GlobalSettings.readFromFile(bloopGlobalSettingsPath, logger)
    } else {
      Right(GlobalSettings.default)
    }
  }

  /**
   * Returns the path of the running program, imitating `sys.argv[0]` in Python.
   *
   * If the program has been compiled to GraalVM Native, it returns a path to
   * the native application. If the program is a JVM-based application, the
   * path points to the JAR that owns this class (e.g. bloopgun jar).
   */
  def executablePath: Option[Path] = {
    try {
      Some(Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().toURI))
    } catch {
      case NonFatal(_) => None
    }
  }

  /**
   * Reads all jvm options required to start the Bloop server, in order of priority:
   *
   * Parses `-J` prefixed jvm options in the arguments passed to the server command.
   *
   * Prior to 1.5.0 it used to also:
   * 1. Read `$$HOME/.bloop/.jvmopts` file.
   * 2. Read `.jvmopts` file right next to the location of the bloop server jar.
   * Now, it only logs a warning if the file detected.
   *
   * Returns a list of jvm options with no `-J` prefix.
   */
  def detectJvmOptionsForServer(
      server: LocatedServer,
      serverArgs: List[String],
      logger: Logger
  ): List[String] = {
    def detectJvmOptsFile(jvmOptsFile: Path): Unit = {
      if (Files.exists(jvmOptsFile)) {
        logger.warn(s"Since Bloop 1.5.0 ${jvmOptsFile.toAbsolutePath()} is ignored.")
      }
    }
    val jvmServerArgs = serverArgs.filter(_.startsWith("-J"))
    detectJvmOptsFile(Environment.defaultBloopDirectory.resolve(".jvmopts"))
    server match {
      case AvailableAtPath(binary) => detectJvmOptsFile(binary.getParent.resolve(".jvmopts"))
      case _ => Nil
    }

    jvmServerArgs.map(_.stripPrefix("-J"))
  }

  // TODO: Add more options to better tweak GC based on benchmarks
  val PerformanceSensitiveOptsForBloop: List[String] = List(
    "-Xss4m",
    "-XX:MaxInlineLevel=20", // Specific option for faster C2, ignored by GraalVM
    "-XX:+UseParallelGC" // Full parallel GC is the best choice for Scala compilation
  )
}
