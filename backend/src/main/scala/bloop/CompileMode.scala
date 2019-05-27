package bloop

import _root_.monix.eval.Task
import scala.concurrent.Promise
import bloop.io.AbsolutePath
import xsbti.compile.Signature

/**
 * Defines the mode in which compilation should run.
 */
sealed trait CompileMode {
  def oracle: CompilerOracle
  def picklesDir: AbsolutePath
}

object CompileMode {
  case class Sequential(
      picklesDir: AbsolutePath,
      oracle: CompilerOracle
  ) extends CompileMode

  final case class Pipelined(
      completeJavaCompilation: Promise[Unit],
      fireJavaCompilation: Task[JavaSignal],
      picklesDir: AbsolutePath,
      oracle: CompilerOracle,
      separateJavaAndScala: Boolean
  ) extends CompileMode
}
