package sbt.internal.inc.bloop

import java.nio.file.Path

import xsbti.{ComponentProvider, GlobalLock}
import sbt.internal.inc.ZincComponentCompiler

object ZincInternals {
  def latestVersion: String = ZincComponentCompiler.incrementalVersion
  def getComponentProvider(bloopHome: Path): ComponentProvider =
    ZincComponentCompiler.getDefaultComponentProvider(bloopHome.resolve("components").toFile())
  def getGlobalLock: GlobalLock = ZincComponentCompiler.getDefaultLock
}
