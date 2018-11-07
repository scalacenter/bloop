package bloop.tasks

import scala.util.Properties

import bloop.{CompilerCache, ScalaInstance}
import bloop.io.Paths
import bloop.logging.{RecordingLogger, Logger}
import sbt.internal.inc.bloop.ZincInternals

object CompilationHelpers {
  final val componentProvider =
    ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))

  private var singleCompilerCache: CompilerCache = null
  def getCompilerCache(logger: Logger): CompilerCache = synchronized {
    if (singleCompilerCache != null) singleCompilerCache
    else {
      val jars = Paths.getCacheDirectory("scala-jars")
      singleCompilerCache = new CompilerCache(componentProvider, jars, logger, Nil)
      singleCompilerCache
    }
  }

  final val scalaInstance: ScalaInstance = {
    val logger = new RecordingLogger
    ScalaInstance.resolve(
      "org.scala-lang",
      "scala-compiler",
      Properties.versionNumberString,
      logger
    )
  }
}
