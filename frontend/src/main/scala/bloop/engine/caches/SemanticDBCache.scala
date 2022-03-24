package bloop.engine.caches

import java.nio.file.Path

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import bloop.DependencyResolution
import bloop.SemanticDBCacheLock
import bloop.io.AbsolutePath
import bloop.io.Paths
import bloop.logging.Logger

import sbt.internal.inc.BloopComponentCompiler
import sbt.internal.inc.BloopComponentManager
import sbt.internal.inc.IfMissing

object SemanticDBCache {
  private def fetchPlugin(
      artifact: DependencyResolution.Artifact,
      logger: Logger
  ): Either[String, AbsolutePath] = {

    def attemptResolution: Either[String, AbsolutePath] = {
      import bloop.engine.ExecutionContext.ioScheduler
      DependencyResolution.resolveWithErrors(
        List(artifact),
        logger
      )(ioScheduler) match {
        case Left(error) => Left(error.getMessage())
        case Right(paths) =>
          paths.find(_.syntax.contains(artifact.module)) match {
            case Some(pluginPath) => Right(pluginPath)
            case None =>
              Left(
                s"Missing ${artifact.module} plugin in resolved jars ${paths.map(_.syntax).mkString(",")}"
              )
          }
      }
    }

    if (artifact.version == "latest.release") {
      attemptResolution
    } else {
      val provider =
        BloopComponentCompiler.getComponentProvider(Paths.getCacheDirectory("semanticdb"))
      val manager =
        new BloopComponentManager(SemanticDBCacheLock, provider, secondaryCacheDir = None)
      val semanticDBId = s"${artifact.organization}.${artifact.module}.${artifact.version}"
      Try(manager.file(semanticDBId)(IfMissing.Fail)) match {
        case Success(pluginPath) => Right(AbsolutePath(pluginPath))
        case Failure(exception) =>
          val resolvedPlugin = attemptResolution
          resolvedPlugin.foreach(plugin => manager.define(semanticDBId, Seq(plugin.toFile)))
          resolvedPlugin
      }
    }
  }

  @volatile private var latestResolvedScalaSemanticDB: Path = null
  def fetchScalaPlugin(
      scalaVersion: String,
      version: String,
      logger: Logger
  ): Either[String, AbsolutePath] = {
    val organization = "org.scalameta"
    val module = s"semanticdb-scalac_$scalaVersion"
    val artifact = DependencyResolution.Artifact(organization, module, version)
    if (artifact.version == "latest.release") {
      // Only resolve once per bloop server invocation to avoid excessive overhead
      latestResolvedScalaSemanticDB.synchronized {
        if (latestResolvedScalaSemanticDB != null)
          Right(AbsolutePath(latestResolvedScalaSemanticDB))
        else {
          val latestResolvedPlugin = fetchPlugin(artifact, logger)
          latestResolvedPlugin.foreach(plugin => latestResolvedScalaSemanticDB = plugin.underlying)
          latestResolvedPlugin
        }
      }
    } else fetchPlugin(artifact, logger)
  }

  @volatile private var latestResolvedJavaSemanticDB: Path = null
  def fetchJavaPlugin(
      version: String,
      logger: Logger
  ): Either[String, AbsolutePath] = {
    val organization = "com.sourcegraph"
    val module = "semanticdb-javac"
    val artifact = DependencyResolution.Artifact(organization, module, version)
    if (artifact.version == "latest.release") {
      // Only resolve once per bloop server invocation to avoid excessive overhead
      latestResolvedJavaSemanticDB.synchronized {
        if (latestResolvedJavaSemanticDB != null) Right(AbsolutePath(latestResolvedJavaSemanticDB))
        else {
          val latestResolvedPlugin = fetchPlugin(artifact, logger)
          latestResolvedPlugin.foreach(plugin => latestResolvedJavaSemanticDB = plugin.underlying)
          latestResolvedPlugin
        }
      }
    } else fetchPlugin(artifact, logger)
  }
}
