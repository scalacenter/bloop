package bloop.engine
import bloop.logging.Logger
import coursier.core.Repository
import bloop.io.AbsolutePath
import bloop.io.Paths
import bloop.DependencyResolution
import scala.util.Try
import java.nio.file.Files
import xsbti.ComponentProvider
import xsbti.GlobalLock
import java.io.File
import java.util.concurrent.Callable
import sbt.internal.inc.bloop.ZincInternals
import sbt.internal.inc.ZincComponentManager
import sbt.internal.inc.IfMissing
import scala.util.Failure
import scala.util.Success
import bloop.ComponentLock

object SemanticDBCache {

  val latestRelease = "latest.release"

  private object SemanticDBCacheLock extends ComponentLock
  private val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("semanticdb"))
  private val zincComponentManager =
    new ZincComponentManager(SemanticDBCacheLock, provider, secondaryCacheDir = None)

  def findSemanticDBPlugin(
      scalaVersion: String,
      semanticDBVersion: String,
      logger: Logger
  ): Option[AbsolutePath] = {
    Try {
      resolveCache(
        "org.scalameta",
        s"semanticdb-scalac_$scalaVersion",
        semanticDBVersion,
        logger
      )(bloop.engine.ExecutionContext.ioScheduler)
    }.toOption.flatten
  }

  private def resolveCache(
      organization: String,
      module: String,
      version: String,
      logger: Logger,
      additionalRepositories: Seq[Repository] = Nil
  )(implicit ec: scala.concurrent.ExecutionContext): Option[AbsolutePath] = {

    def getFromResolution: Option[AbsolutePath] = {
      DependencyResolution
        .resolve(organization, module, version, logger, additionalRepositories)
        .find(_.toString().contains("semanticdb-scalac"))
    }
    if (version == latestRelease) {
      getFromResolution
    } else {
      val semanticDBId = s"$organization.$module.$version"
      Try(zincComponentManager.file(semanticDBId)(IfMissing.Fail)) match {
        case Failure(exception) =>
          val resolved = getFromResolution
          resolved match {
            case None =>
              logger.warn("Could not find semanticDB version:\n" + exception.getMessage())
            case Some(value) =>
              zincComponentManager.define(semanticDBId, Seq(value.toFile))
          }
          resolved
        case Success(value) => Some(AbsolutePath(value))
      }
    }
  }
}
