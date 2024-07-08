package bloop.dap

import java.net.URLClassLoader
import java.nio.file.Path

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

  override def resolveDecoder(scalaVersion: ScalaVersion): Try[Seq[Path]] = {
    getOrTryUpdate(decoderCache, scalaVersion) {
      val decoderModule = s"${BuildInfo.decoderName}_${scalaVersion.binaryVersion}"
      val artifact = Artifact(BuildInfo.organization, decoderModule, BuildInfo.version)
      DependencyResolution
        .resolveWithErrors(List(artifact), logger)
        .map(_.map(_.underlying).toSeq)
        .toTry
    }
  }

  private def toClassLoader(jars: Array[AbsolutePath], log: Boolean): ClassLoader = {
    if (log) jars.foreach(jar => logger.info(jar.toString))
    new URLClassLoader(jars.map(_.underlying.toUri.toURL), null)
  }

  private def getOrTryUpdate[T](
      cache: mutable.Map[ScalaVersion, T],
      scalaVersion: ScalaVersion
  )(resolve: => Try[T]): Try[T] = {
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
  private val decoderCache: mutable.Map[ScalaVersion, Seq[Path]] = mutable.Map.empty
}
