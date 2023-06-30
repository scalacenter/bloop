package bloop.engine.caches

import java.nio.file.Path

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import bloop.DependencyResolution
import bloop.SemanticDBCacheLock
import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import bloop.io.Paths
import bloop.logging.Logger
import bloop.task.Task

import sbt.internal.inc.BloopComponentCompiler
import sbt.internal.inc.BloopComponentManager
import sbt.internal.inc.IfMissing
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Future

object SemanticDBCache {
  // to avoid resolving the same fallback semanticdb version multiple times
  private val supportedFallbackSemanticdbVersions = new ConcurrentHashMap[String, String]()
  private def fetchPlugin(
      artifact: DependencyResolution.Artifact,
      logger: Logger
  ): Either[String, AbsolutePath] = {

    def attemptResolution: Either[String, AbsolutePath] = {
      DependencyResolution.resolveWithErrors(
        List(artifact),
        logger
      ) match {
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
        case Failure(_) =>
          val resolvedPlugin = attemptResolution
          resolvedPlugin.foreach(plugin => manager.define(semanticDBId, Seq(plugin.toFile)))
          resolvedPlugin
      }
    }
  }

  private def fallbackSemanticdbFetch(
      scalaVersion: String,
      artifact: DependencyResolution.Artifact,
      logger: Logger
  ): Either[String, AbsolutePath] = {
    // if scala version is no longer supported find the latest supported semanticdb version
    def versionFuture =
      Future {
        coursierapi.Complete
          .create()
          .withScalaVersion(scalaVersion)
          .withScalaBinaryVersion(scalaVersion.split('.').take(2).mkString("."))
          .withInput(s"org.scalameta:semanticdb-scalac_$scalaVersion:")
          .complete()
          .getCompletions()
          .asScala
          .lastOption
      }(ExecutionContext.ioScheduler)

    def version =
      try
        Await.result(versionFuture, FiniteDuration(10, "s"))
      catch {
        case _: Throwable => None
      }

    Option(supportedFallbackSemanticdbVersions.get(scalaVersion)).orElse(version) match {
      case None =>
        Left(s"After retry no existing semanticdb version found for scala version $scalaVersion")
      case Some(semanticdbVersion) =>
        supportedFallbackSemanticdbVersions.put(scalaVersion, semanticdbVersion)
        fetchPlugin(artifact.copy(version = semanticdbVersion), logger)
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
    } else {
      fetchPlugin(artifact, logger) match {
        case Right(plugin) => Right(plugin)
        case Left(error) =>
          fallbackSemanticdbFetch(scalaVersion, artifact, logger) match {
            case Left(newError) => Left(error + "\n" + newError)
            case Right(plugin) => Right(plugin)
          }
      }
    }
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
