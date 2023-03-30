package bloop.cli.options

import caseapp._
import com.github.plokhotnyuk.jsoniter_scala.core._
import coursier.cache.FileCache
import coursier.core.{Version => Ver}
import coursier.util.Task

import java.nio.file.Paths

import bloop.rifle.internal.Constants
import bloop.rifle.{BloopRifleConfig, BloopVersion}
import scala.concurrent.duration.{Duration, FiniteDuration}
import bloop.cli.Directories
import bloop.cli.Logger
import bloop.cli.BloopJson
import bloop.cli.BloopClassPath


// format: off
final case class SharedCompilationServerOptions(
  @HelpMessage("Host the compilation server should bind to")
  @ValueDescription("host")
  @Hidden
    host: Option[String] = None,
  @HelpMessage("Port the compilation server should bind to (pass `-1` to pick a random port)")
  @ValueDescription("port|-1")
  @Hidden
    port: Option[Int] = None,
  @HelpMessage("Daemon directory of the Bloop daemon (directory with lock, pid, and socket files)")
  @ValueDescription("path")
  @Hidden
    daemonDir: Option[String] = None,

  @HelpMessage("If Bloop isn't already running, the version we should start")
  @ValueDescription("version")
  @Hidden
    bloopVersion: Option[String] = None,

  @Hidden
  @HelpMessage("Maximum duration to wait for the compilation server to start up")
  @ValueDescription("duration")
    startupTimeout: Option[String] = None,

  @HelpMessage("Include default JVM options for Bloop")
  @Hidden
    useDefaultJavaOpts: Boolean = true,
  @Hidden
    javaOpt: List[String] = Nil,
  @HelpMessage("Bloop global options file")
  @Hidden
    globalOptionsFile: Option[String] = None,

  @HelpMessage("Working directory for Bloop, if it needs to be started")
  @Hidden
    workingDir: Option[String] = None
) {
  // format: on

  private def parseDuration(name: String, valueOpt: Option[String]): Option[FiniteDuration] =
    valueOpt.map(_.trim).filter(_.nonEmpty).map(Duration(_)).map {
      case d: FiniteDuration => d
      case d                 => sys.error(s"Expected finite $name duration, got $d")
    }

  def bloopStartupTimeoutDuration: Option[FiniteDuration] =
    parseDuration("connection server startup timeout", startupTimeout)

  def retainedBloopVersion: BloopRifleConfig.BloopVersionConstraint =
    bloopVersion
      .map(_.trim)
      .filter(_.nonEmpty)
      .fold[BloopRifleConfig.BloopVersionConstraint](BloopRifleConfig.AtLeast(
        BloopVersion(Constants.bloopVersion)
      ))(v => BloopRifleConfig.Strict(BloopVersion(v)))

  def bloopDefaultJvmOptions(logger: Logger): Option[List[String]] = {
    val filePathOpt = globalOptionsFile.filter(_.trim.nonEmpty).map(os.Path(_, os.pwd))
    for (filePath <- filePathOpt)
      yield
        if (os.exists(filePath) && os.isFile(filePath))
          try {
            val content   = os.read.bytes(filePath)
            val bloopJson = readFromArray(content)(BloopJson.codec)
            bloopJson.javaOptions
          }
          catch {
            case e: Throwable =>
              logger.message(s"Error parsing global bloop config in '$filePath':")
              e.printStackTrace()
              List.empty
          }
        else
          // logger.message(s"Bloop global options file '$filePath' not found.")
          List.empty
  }

  def bloopRifleConfig(
    logger: Logger,
    cache: FileCache[Task],
    javaPath: String,
    directories: => Directories,
    javaV: Option[Int] = None
  ): BloopRifleConfig = {

    val portOpt = port.filter(_ != 0) match {
      case Some(n) if n < 0 =>
        Some(bloop.rifle.internal.Util.randomPort())
      case other => other
    }
    val address =
      (
        host.filter(_.nonEmpty),
        portOpt,
        daemonDir.filter(_.nonEmpty)
      ) match {
        case (_, _, Some(path)) =>
          BloopRifleConfig.Address.DomainSocket(Paths.get(path))
        case (None, None, None) =>
          val isBloopMainLine = Ver(retainedBloopVersion.version.raw) < Ver("1.4.12")
          if (isBloopMainLine)
            BloopRifleConfig.Address.Tcp(
              host = BloopRifleConfig.defaultHost,
              port = BloopRifleConfig.defaultPort
            )
          else
            BloopRifleConfig.Address.DomainSocket(directories.bloopDaemonDir.toNIO)
        case (hostOpt, portOpt0, _) =>
          BloopRifleConfig.Address.Tcp(
            host = hostOpt.getOrElse(BloopRifleConfig.defaultHost),
            port = portOpt0.getOrElse(BloopRifleConfig.defaultPort)
          )
      }

    val workingDir0 = workingDir
      .filter(_.trim.nonEmpty)
      .map(os.Path(_, os.pwd))
      .getOrElse(directories.bloopWorkingDir)
    val baseConfig = BloopRifleConfig.default(
      address,
      v => BloopClassPath.bloopClassPath(logger, cache, v),
      workingDir0.toIO
    )

    baseConfig.copy(
      javaPath = javaPath,
      initTimeout = bloopStartupTimeoutDuration.getOrElse(baseConfig.initTimeout),
      javaOpts =
        (if (useDefaultJavaOpts) baseConfig.javaOpts
         else Nil) ++ javaOpt ++ bloopDefaultJvmOptions(logger).getOrElse(Nil),
      minimumBloopJvm = javaV.getOrElse(8),
      retainedBloopVersion = retainedBloopVersion
    )
  }
}

object SharedCompilationServerOptions {
  lazy val parser: Parser[SharedCompilationServerOptions]                           = Parser.derive
  implicit lazy val parserAux: Parser.Aux[SharedCompilationServerOptions, parser.D] = parser
  implicit lazy val help: Help[SharedCompilationServerOptions]                      = Help.derive
}
