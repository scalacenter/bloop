package bloop.tasks

import scala.util.Properties

import java.util.Optional

import bloop.{CompilerCache, ScalaInstance}
import bloop.io.Paths
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}
import sbt.internal.inc.bloop.ZincInternals

object CompilationHelpers {

  val emptyPreviousResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])

  def scalaInstance: ScalaInstance =
    ScalaInstance("org.scala-lang", "scala-compiler", Properties.versionNumberString)

  val componentProvider =
    ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))

  val compilerCache = new CompilerCache(componentProvider, Paths.getCacheDirectory("scala-jars"))

}
