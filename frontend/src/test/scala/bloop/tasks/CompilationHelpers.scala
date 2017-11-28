package bloop.tasks

import java.io.File

import scala.util.Properties
import java.util.Optional

import bloop.{CompilerCache, ScalaInstance}
import bloop.io.AbsolutePath
import bloop.io.Paths
import bloop.logging.Logger
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}
import sbt.internal.inc.bloop.ZincInternals
import sbt.librarymanagement.Resolver

object CompilationHelpers {
  final val emptyPreviousResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])
  final val componentProvider =
    ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))

  private val ScriptedResolverId = "zinc-scripted-local"
  private val ScriptedResolveCacheDir: File =
    AbsolutePath(s".ivy2/$ScriptedResolverId").toFile
  private val ScriptedResolver: Resolver =
    Resolver.file(ScriptedResolverId, ScriptedResolveCacheDir)(Resolver.ivyStylePatterns)

  final val compilerCache: CompilerCache = {
    val scalaJarsPath = Paths.getCacheDirectory("scala-jars")
    new CompilerCache(componentProvider, scalaJarsPath, new Logger(), List(ScriptedResolver))
  }

  final val scalaInstance: ScalaInstance =
    ScalaInstance.resolve("org.scala-lang", "scala-compiler", Properties.versionNumberString)
}
