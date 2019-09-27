package bloop.bloop4j.util

import java.nio.file.{Files, Path, Paths}

import java.{util => ju}
import scala.util.control.NonFatal

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
      case NonFatal(t) => None
    }
  }
}
