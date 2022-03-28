package bloop.engine.caches

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import bloop.DependencyResolution
import bloop.ExpressionCompilerCacheLock
import bloop.io.AbsolutePath
import bloop.io.Paths
import bloop.logging.Logger

import sbt.internal.inc.BloopComponentCompiler
import sbt.internal.inc.BloopComponentManager
import sbt.internal.inc.IfMissing

object ExpressionCompilerCache {
  import ch.epfl.scala.debugadapter.BuildInfo._

  private val cacheDirectory = Paths.getCacheDirectory(expressionCompilerName)
  private val provider = BloopComponentCompiler.getComponentProvider(cacheDirectory)
  private val manager =
    new BloopComponentManager(ExpressionCompilerCacheLock, provider, secondaryCacheDir = None)

  def fetch(
      scalaVersion: String,
      logger: Logger
  ): Either[String, AbsolutePath] = {
    val module = s"${expressionCompilerName}_$scalaVersion"
    val expressionCompilerId = s"$expressionCompilerOrganization.$module.$expressionCompilerVersion"

    def attemptResolution(): Either[String, AbsolutePath] = {
      import bloop.engine.ExecutionContext.ioScheduler
      val artifact = DependencyResolution.Artifact(
        expressionCompilerOrganization,
        module,
        expressionCompilerVersion
      )
      DependencyResolution.resolveWithErrors(List(artifact), logger)(ioScheduler) match {
        case Left(error) => Left(error.getMessage())
        case Right(paths) =>
          paths
            .find(_.syntax.contains(module))
            .toRight(s"Missing $module in resolved jars ${paths.map(_.syntax).mkString(",")}")
      }
    }

    Try(manager.file(expressionCompilerId)(IfMissing.Fail)) match {
      case Success(pluginPath) => Right(AbsolutePath(pluginPath))
      case Failure(exception) =>
        val resolution = attemptResolution()
        resolution.foreach(jar => manager.define(expressionCompilerId, Seq(jar.toFile)))
        resolution
    }
  }
}
