package bloop.tasks

import java.io.File

import scala.util.Properties

import bloop.{CompilerCache, ScalaInstance}
import bloop.io.AbsolutePath
import bloop.io.Paths
import bloop.logging.Logger
import sbt.internal.inc.bloop.ZincInternals
import sbt.librarymanagement.Resolver

object CompilationHelpers {
  final val componentProvider =
    ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))

  private val ScriptedResolverId = "zinc-scripted-local"
  private val ScriptedResolveCacheDir: File =
    AbsolutePath(s".ivy2/$ScriptedResolverId").toFile
  private val ScriptedResolver: Resolver =
    Resolver.file(ScriptedResolverId, ScriptedResolveCacheDir)(Resolver.ivyStylePatterns)

  private var singleCompilerCache: CompilerCache = null
  def getCompilerCache(logger: Logger): CompilerCache = synchronized {
    if (singleCompilerCache != null) singleCompilerCache
    else {
      val jars = Paths.getCacheDirectory("scala-jars")
      val resolvers = List(ScriptedResolver)
      singleCompilerCache = new CompilerCache(componentProvider, jars, logger, resolvers)
      singleCompilerCache
    }
  }

  final val scalaInstance: ScalaInstance =
    ScalaInstance.resolve("org.scala-lang", "scala-compiler", Properties.versionNumberString)
}
