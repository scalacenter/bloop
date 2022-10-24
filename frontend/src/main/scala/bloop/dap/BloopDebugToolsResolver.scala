package bloop.dap

import java.net.URLClassLoader

import scala.collection.mutable
import scala.util.Success
import scala.util.Try

import ch.epfl.scala.debugadapter.BuildInfo
import ch.epfl.scala.debugadapter.DebugToolsResolver
import ch.epfl.scala.debugadapter.ScalaVersion

import bloop.DependencyResolution
import bloop.DependencyResolution.Artifact
import bloop.io.AbsolutePath
import bloop.logging.Logger

/**
 * Resolve the tools for the debugger in the form of classloaders.
 * The debug tools are comprised of:
 * - the expression compiler
 * - the step filter
 */
class BloopDebugToolsResolver(logger: Logger) extends DebugToolsResolver {
  import BloopDebugToolsResolver._
  override def resolveExpressionCompiler(scalaVersion: ScalaVersion): Try[ClassLoader] = {
    getOrTryUpdate(expressionCompilerCache, scalaVersion) {
      val module = s"${BuildInfo.expressionCompilerName}_$scalaVersion"
      val artifact = Artifact(BuildInfo.organization, module, BuildInfo.version)
      DependencyResolution
        .resolveWithErrors(List(artifact), logger)
        .map(jars => toClassLoader(jars, false))
        .toTry
    }
  }

  override def resolveStepFilter(scalaVersion: ScalaVersion): Try[ClassLoader] = {
    getOrTryUpdate(stepFilterCache, scalaVersion) {
      val stepFilterModule = s"${BuildInfo.scala3StepFilterName}_${scalaVersion.binaryVersion}"
      val stepFilter = Artifact(BuildInfo.organization, stepFilterModule, BuildInfo.version)
      val tastyCore = Artifact("org.scala-lang", "tasty-core_3", scalaVersion.value)
      DependencyResolution
        .resolveWithErrors(List(stepFilter, tastyCore), logger)
        .map(jars => toClassLoader(jars, true))
        .toTry
    }
  }

  private def toClassLoader(jars: Array[AbsolutePath], log: Boolean): ClassLoader = {
    if (log) jars.foreach(jar => logger.info(jar.toString))
    new URLClassLoader(jars.map(_.underlying.toUri.toURL), null)
  }

  private def getOrTryUpdate(
      cache: mutable.Map[ScalaVersion, ClassLoader],
      scalaVersion: ScalaVersion
  )(resolve: => Try[ClassLoader]): Try[ClassLoader] = {
    if (cache.contains(scalaVersion)) Success(cache(scalaVersion))
    else
      resolve.map { classLoader =>
        cache.update(scalaVersion, classLoader)
        classLoader
      }
  }
}

object BloopDebugToolsResolver {
  private val expressionCompilerCache: mutable.Map[ScalaVersion, ClassLoader] = mutable.Map.empty
  private val stepFilterCache: mutable.Map[ScalaVersion, ClassLoader] = mutable.Map.empty
}
