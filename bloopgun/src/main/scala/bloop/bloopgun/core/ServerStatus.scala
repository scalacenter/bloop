package bloop.bloopgun.core

import java.nio.file.Path

import snailgun.logging.Logger

sealed trait ServerStatus
sealed trait LocatedServer extends ServerStatus
case class AvailableWithCommand(binary: List[String]) extends LocatedServer
case class AvailableAtPath(binary: Path) extends LocatedServer
case class ResolvedAt(files: Seq[Path]) extends LocatedServer
case class ListeningAndAvailableAt(binary: List[String]) extends ServerStatus

object ServerStatus {
  def resolveServer(bloopVersion: String, logger: Logger): Option[ResolvedAt] = {
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
