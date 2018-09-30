package bloop.engine.tasks.compilation

import java.net.URI
import java.util.Optional

import bloop.data.Project
import bloop.Compiler
import bloop.reporter.Problem
import bloop.util.CacheHashCode
import monix.eval.Task
import sbt.internal.inc.bloop.JavaSignal

import scala.util.Try

sealed trait CompileResult[+R] {
  def bundle: CompileBundle
  def result: R
}

sealed trait PartialCompileResult extends CompileResult[Task[Compiler.Result]] {
  def bundle: CompileBundle
  def result: Task[Compiler.Result]

  def toFinalResult: Task[FinalCompileResult] =
    result.map(res => FinalCompileResult(bundle, res))
}

object PartialCompileResult {
  def apply(
      bundle: CompileBundle,
      pickleURI: Try[Optional[URI]],
      completeJava: Task[JavaSignal],
      result: Task[Compiler.Result]
  ): PartialCompileResult = {
    pickleURI match {
      case scala.util.Success(opt) =>
        PartialSuccess(bundle, opt, completeJava, result)
      case scala.util.Failure(CompileExceptions.CompletePromise) =>
        PartialSuccess(bundle, Optional.empty(), completeJava, result)
      case scala.util.Failure(t) =>
        PartialFailure(bundle, t, result)
    }
  }
}

case class PartialFailure(
    bundle: CompileBundle,
    exception: Throwable,
    result: Task[Compiler.Result]
) extends PartialCompileResult
    with CacheHashCode

case class PartialSuccess(
    bundle: CompileBundle,
    pickleURI: Optional[URI],
    completeJava: Task[JavaSignal],
    result: Task[Compiler.Result]
) extends PartialCompileResult
    with CacheHashCode

case class FinalCompileResult(
    bundle: CompileBundle,
    result: Compiler.Result
) extends CompileResult[Compiler.Result]
    with CacheHashCode

object FinalCompileResult {
  import scalaz.Show
  final implicit val showFinalResult: Show[FinalCompileResult] = new Show[FinalCompileResult] {
    private def seconds(ms: Double): String = s"${ms}ms"
    override def shows(r: FinalCompileResult): String = {
      val projectName = r.bundle.project.name
      r.result match {
        case Compiler.Result.Empty => s"${projectName} (empty)"
        case Compiler.Result.Cancelled(ms) => s"${projectName} (cancelled, lasted ${ms}ms)"
        case Compiler.Result.Success(_, _, ms) => s"${projectName} (success ${ms}ms)"
        case Compiler.Result.Blocked(on) => s"${projectName} (blocked on ${on.mkString(", ")})"
        case Compiler.Result.GlobalError(problem) =>
          s"${projectName} (failed with global error ${problem})"
        case Compiler.Result.Failed(problems, t, ms) =>
          val extra = t match {
            case Some(t) => s"exception '${t.getMessage}', "
            case None => ""
          }
          s"${projectName} (failed with ${Problem.count(problems)}, $extra${ms}ms)"
      }
    }
  }

}
