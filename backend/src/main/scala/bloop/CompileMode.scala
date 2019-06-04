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
}

object CompileMode {
  case class Sequential(
      oracle: CompilerOracle
  ) extends CompileMode

  final case class Pipelined(
      completeJavaCompilation: Promise[Unit],
      finishedCompilation: Promise[Option[CompileProducts]],
      fireJavaCompilation: Task[JavaSignal],
      oracle: CompilerOracle,
      separateJavaAndScala: Boolean
  ) extends CompileMode
}
