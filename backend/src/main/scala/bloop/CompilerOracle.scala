package bloop

import java.io.File

/**
 * A compiler oracle is an entity that provides routines to answer
 * questions that come up during the scheduling of compilation tasks.
 */
abstract class CompilerOracle[T] {
  def askForJavaSourcesOfIncompleteCompilations: List[File]
  def askForClassesDirectory(inputs: Compiler.RequestInputs): File
  def learnScheduledCompilations(scheduled: List[T]): CompilerOracle[T]
}
