package bloop.tasks

import java.io.File

import scala.util.Properties

import bloop.{CompilerCache, ScalaInstance}
import bloop.io.AbsolutePath
import bloop.io.Paths
import bloop.logging.{RecordingLogger, Logger}
import sbt.internal.inc.bloop.ZincInternals
import sbt.librarymanagement.Resolver

object CompilationHelpers {
  final val componentProvider =
    ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))

  private var singleCompilerCache: CompilerCache = null
  def getCompilerCache(logger: Logger): CompilerCache = synchronized {
    if (singleCompilerCache != null) singleCompilerCache
    else {
      val jars = Paths.getCacheDirectory("scala-jars")
      singleCompilerCache = new CompilerCache(componentProvider, jars, logger)
      singleCompilerCache
    }
  }

  final val scalaInstance: ScalaInstance = {
    val logger = new RecordingLogger
    ScalaInstance.resolve("org.scala-lang",
                          "scala-compiler",
                          Properties.versionNumberString,
                          logger)
  }
}
