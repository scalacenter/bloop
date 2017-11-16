package bloop
package tasks

import sbt.internal.inc.bloop.ZincInternals

import bloop.io.IO

object CompilationHelpers {

  def scalaInstance: ScalaInstance =
    ScalaInstance("org.scala-lang", "scala-compiler", "2.12.4")

  val componentProvider =
    ZincInternals.getComponentProvider(IO.getCacheDirectory("components"))

  val compilerCache = new CompilerCache(componentProvider, IO.getCacheDirectory("scala-jars"))

}
