package bloop
package tasks

import scala.util.Properties
import sbt.internal.inc.bloop.ZincInternals

import bloop.io.Paths

object CompilationHelpers {

  def scalaInstance: ScalaInstance =
    ScalaInstance("org.scala-lang", "scala-compiler", Properties.versionNumberString)

  val componentProvider =
    ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))

  val compilerCache = new CompilerCache(componentProvider, Paths.getCacheDirectory("scala-jars"))

}
