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
import bloop.SemanticDBCacheLock

object SemanticDBCache {

  private val latestRelease = "latest.release"

  def findSemanticDBPlugin(
      scalaVersion: String,
      supportedScalaVersion: List[String],
      semanticDBVersion: String,
      logger: Logger
  ): Option[AbsolutePath] = {
    if (!supportedScalaVersion.contains(scalaVersion)) {
      logger.displayWarningToUser(
        s"$scalaVersion is not supported for semanticDB version $semanticDBVersion"
      )
      None
    } else
      Try {
        resolveFromCache(
          "org.scalameta",
          s"semanticdb-scalac_$scalaVersion",
          semanticDBVersion,
          logger
        )
      }.toOption.flatten

  }

  private def resolveFromCache(
      organization: String,
      module: String,
      version: String,
      logger: Logger,
      additionalRepositories: Seq[Repository] = Nil
  ): Option[AbsolutePath] = {
    val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("semanticdb"))
    val manager =
      new ZincComponentManager(SemanticDBCacheLock, provider, secondaryCacheDir = None)
    def getFromResolution: Option[AbsolutePath] = {
      val all = DependencyResolution
        .resolve(
          organization,
          module,
          version,
          logger,
          additionalRepositories,
          shouldReportErrors = true
        )(
          bloop.engine.ExecutionContext.ioScheduler
        )
      all.find(_.toString().contains("semanticdb-scalac"))
    }
    if (version == latestRelease) {
      getFromResolution
    } else {
      val semanticDBId = s"$organization.$module.$version"
      Try(manager.file(semanticDBId)(IfMissing.Fail)) match {
        case Failure(exception) =>
          val resolved = getFromResolution
          resolved match {
            case Some(value) =>
              manager.define(semanticDBId, Seq(value.toFile))
            case None =>
              logger.warn(
                s"Could not resolve semanticDB version $version"
              )
          }
          resolved
        case Success(value) => Some(AbsolutePath(value))
      }
    }
  }
}
