package bloop.engine.tasks.compilation

import bloop.CompilerOracle
import java.io.File
import bloop.ScalaSig
import bloop.io.AbsolutePath
import xsbti.compile.Signature
import scala.collection.mutable

final class SimpleOracle extends CompilerOracle {
  def blockUntilMacroClasspathIsReady(usedMacroSymbol: String): Unit = ()
  def askForJavaSourcesOfIncompleteCompilations: List[File] = Nil
  def isPipeliningEnabled: Boolean = false
  def collectDownstreamSignatures: Array[Signature] = new Array[Signature](0)
  def startDownstreamCompilations(sigs: Array[Signature]): Unit = ()

  private val definedMacros = new mutable.HashSet[String]()
  def registerDefinedMacro(definedMacroSymbol: String): Unit = definedMacros.+=(definedMacroSymbol)
  def collectDefinedMacroSymbols: Array[String] = definedMacros.toArray
}
