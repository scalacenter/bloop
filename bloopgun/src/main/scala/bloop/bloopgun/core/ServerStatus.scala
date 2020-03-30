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
