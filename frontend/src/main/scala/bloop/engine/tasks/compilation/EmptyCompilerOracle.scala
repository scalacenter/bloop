package bloop.engine.tasks.compilation

import bloop.CompilerOracle
import java.io.File
import bloop.ScalaSig
import bloop.io.AbsolutePath

final object EmptyCompilerOracle extends CompilerOracle {
  def blockUntilMacroClasspathIsReady(usedMacroSymbol: String): Unit = ()
  def registerDefinedMacro(definedMacroSymbol: String): Unit = ()
  def askForJavaSourcesOfIncompleteCompilations: List[File] = Nil
  def startDownstreamCompilations(
      pickleDir: AbsolutePath,
      pickles: List[ScalaSig]
  ): Unit = ()
}
