package bloop.bloopgun.core

import java.nio.file.Path
import java.nio.file.Files

import bloop.bloopgun.util.Environment
import bloop.bloopgun.core.Shell.StatusCommand
import snailgun.logging.Logger
import bloop.bloopgun.ServerConfig
import java.nio.file.Paths
import bloop.bloopgun.util.Feedback

sealed trait ServerStatus
sealed trait LocatedServer extends ServerStatus
case class AvailableWithCommand(binary: List[String]) extends LocatedServer
case class AvailableAtPath(binary: Path) extends LocatedServer
case class ResolvedAt(files: Seq[Path]) extends LocatedServer
case class ListeningAndAvailableAt(binary: List[String]) extends ServerStatus

object ServerStatus {

  /**
   * Finds server to run in the system if bloop is installed, otherwise
   * resolves bloop server via coursier.
   *
   * This method will keep trying any of these actions until the server has
   * been located:
   *
   *   1. Look for `blp-server` in classpath (common case if bloop was installed
   *      via a package manager, e.g. `arch`, `scoop`, `nix`, `brew`).
   *   2. Look for a `blp-server` located right next to the binary of bloopgun
   *      that is running because `blp-server` and `bloop` are always located
   *      together when bloop is installed.
   *   3. Look for `blp-server` in `$$HOME/.bloop` where bloop installation script
   *      installs bloop if the curl installation method is used. Works in case
   *      the running bloopgun client is a binary dependency of an existing tool.
   *   4. If any of these fail, then we return nothing.
   *
   * The caller can use [[resolveServer]] to fetch the jars of Bloop server via
   * coursier and then run them whenever this method returns `None`.
   */
  def findServerToRun(
      bloopVersion: String,
      serverLocation: Option[Path],
      shell: Shell,
      logger: Logger
  ): Option[LocatedServer] = {
    def keepOnSearchForServer: Option[LocatedServer] = {
      val installedBlpServer = Environment.executablePath.flatMap { clientPath =>
        val defaultBlpServer = clientPath.getParent.resolve("blp-server")
        if (Files.exists(defaultBlpServer)) {
          logger.info(Feedback.DetectedBloopInstallation)
          Some(AvailableAtPath(defaultBlpServer))
        } else {
          logger.debug(s"Missing `blp-server` executable at $defaultBlpServer")
          None
        }
      }

      installedBlpServer.orElse {
        val blpServerUnderHome = Environment.defaultBloopDirectory.resolve("blp-server")
        if (Files.exists(blpServerUnderHome)) {
          logger.info(Feedback.DetectedBloopInstallation)
          Some(AvailableAtPath(blpServerUnderHome))
        } else {
          logger.debug(s"Missing `blp-server` executable at $blpServerUnderHome")
          None
        }
      }
    }

    serverLocation.filter(Files.exists(_)) match {
      case Some(location) => Some(AvailableAtPath(location))
      case None =>
        shell.findCmdInPath("blp-server") match {
          case StatusCommand(0, outputPath0) =>
            val outputPath = outputPath0.trim
            logger.info(Feedback.DetectedBloopInstallation)
            val serverLocationFromPath = Paths.get(outputPath)
            if (Files.exists(serverLocationFromPath)) {
              if (Files.size(serverLocationFromPath) == 0L) keepOnSearchForServer
              else Some(AvailableAtPath(serverLocationFromPath))
            } else Some(AvailableWithCommand(List("blp-server")))
          case StatusCommand(errorCode, errorOutput0) =>
            val errorOutput = if (errorOutput0.isEmpty) errorOutput0 else s": $errorOutput0"
            logger.debug(s"Missing `blp-server` in `$$PATH`$errorOutput")
            keepOnSearchForServer
        }
    }
  }

  def resolveServer(bloopVersion: String, logger: Logger) = {
    import scala.concurrent.ExecutionContext.Implicits.global
    DependencyResolution.resolveWithErrors(
      "ch.epfl.scala",
      "bloop-frontend_2.12",
      bloopVersion,
      logger
    ) match {
      case Right(jars) => Some(ResolvedAt(jars))
      case Left(value) =>
        logger.error("Unexpected error when resolving Bloop server via coursier!")
        logger.error(value.getMessage())
        logger.trace(value)
        None
    }
  }
}
