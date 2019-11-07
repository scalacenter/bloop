package bloop.engine.caches

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
import sbt.internal.inc.BloopComponentManager
import sbt.internal.inc.IfMissing
import scala.util.Failure
import scala.util.Success
import bloop.ComponentLock
import bloop.SemanticDBCacheLock
import java.nio.file.Path
import coursier.error.CoursierError
import sbt.internal.inc.BloopComponentCompiler

object SemanticDBCache {
  @volatile private var latestResolvedSemanticDB: Path = null
  def fetchPlugin(
      scalaVersion: String,
      version: String,
      logger: Logger
  ): Either[String, AbsolutePath] = {
    val organization = "org.scalameta"
    val module = s"semanticdb-scalac_$scalaVersion"
    val semanticDBId = s"$organization.$module.$version"
    val provider =
      BloopComponentCompiler.getComponentProvider(Paths.getCacheDirectory("semanticdb"))
    val manager = new BloopComponentManager(SemanticDBCacheLock, provider, secondaryCacheDir = None)

    def attemptResolution: Either[String, AbsolutePath] = {
      import bloop.engine.ExecutionContext.ioScheduler
      DependencyResolution.resolveWithErrors(
        List(DependencyResolution.Artifact(organization, module, version)),
        logger
      )(ioScheduler) match {
        case Left(error) => Left(error.getMessage())
        case Right(paths) =>
          paths.find(_.syntax.contains("semanticdb-scalac")) match {
            case Some(pluginPath) => Right(pluginPath)
            case None =>
              Left(
                s"Missing semanticdb plugin in resolved jars ${paths.map(_.syntax).mkString(",")}"
              )
          }
      }
    }

    if (version == "latest.release") {
      // Only resolve once per bloop server invocation to avoid excessive overhead
      latestResolvedSemanticDB.synchronized {
        if (latestResolvedSemanticDB != null) Right(AbsolutePath(latestResolvedSemanticDB))
        else {
          val latestResolvedPlugin = attemptResolution
          latestResolvedPlugin.foreach(plugin => latestResolvedSemanticDB = plugin.underlying)
          latestResolvedPlugin
        }
      }
    } else {
      Try(manager.file(semanticDBId)(IfMissing.Fail)) match {
        case Success(pluginPath) => Right(AbsolutePath(pluginPath))
        case Failure(exception) =>
          val resolvedPlugin = attemptResolution
          resolvedPlugin.foreach(plugin => manager.define(semanticDBId, Seq(plugin.toFile)))
          resolvedPlugin
      }
    }
  }
}
