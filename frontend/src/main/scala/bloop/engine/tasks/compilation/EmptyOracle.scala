package bloop.engine.tasks.compilation

import bloop.CompilerOracle
import java.io.File
import bloop.ScalaSig
import bloop.io.AbsolutePath
import xsbti.compile.Signature

final object EmptyOracle extends CompilerOracle {
  def blockUntilMacroClasspathIsReady(usedMacroSymbol: String): Unit = ()
  def registerDefinedMacro(definedMacroSymbol: String): Unit = ()
  def askForJavaSourcesOfIncompleteCompilations: List[File] = Nil
  def isPipeliningEnabled: Boolean = false
  def collectDownstreamSignatures(): Array[Signature] = new Array[Signature](0)
  def startDownstreamCompilations(pickleDir: AbsolutePath, sigs: Array[Signature]): Unit = ()
}
