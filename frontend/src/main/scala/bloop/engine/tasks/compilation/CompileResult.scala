package bloop.engine.tasks.compilation

import java.util.Optional
import java.util.concurrent.CompletableFuture

import bloop.{Compiler, JavaSignal}
import bloop.reporter.Problem
import bloop.util.CacheHashCode
import monix.eval.Task
import xsbti.compile.{EmptyIRStore, IRStore}

import scala.util.Try

sealed trait CompileResult[+R] {
  def bundle: CompileBundle
  def result: R
}

sealed trait PartialCompileResult extends CompileResult[Task[Compiler.Result]] {
  def bundle: CompileBundle
  def result: Task[Compiler.Result]
  def store: IRStore

  def toFinalResult: Task[FinalCompileResult] =
    result.map(res => FinalCompileResult(bundle, res, store))
}

object PartialCompileResult {
  def apply(
      bundle: CompileBundle,
      store: Try[IRStore],
      completeJava: CompletableFuture[Unit],
      javaTrigger: Task[JavaSignal],
      result: Task[Compiler.Result]
  ): PartialCompileResult = {
    store match {
      case scala.util.Success(store) =>
        PartialSuccess(bundle, store, completeJava, javaTrigger, result)
      case scala.util.Failure(CompileExceptions.CompletePromise(store)) =>
        PartialSuccess(bundle, store, completeJava, javaTrigger, result)
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
    with CacheHashCode {
  def store: IRStore = xsbti.compile.EmptyIRStore.getStore()
}

case class PartialSuccess(
    bundle: CompileBundle,
    store: IRStore,
    completeJava: CompletableFuture[Unit],
    javaTrigger: Task[JavaSignal],
    result: Task[Compiler.Result]
) extends PartialCompileResult
    with CacheHashCode

case class FinalCompileResult(
    bundle: CompileBundle,
    result: Compiler.Result,
    store: IRStore
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
        case Compiler.Result.Cancelled(problems, ms) =>
          s"${projectName} (cancelled, failed with ${Problem.count(problems)}, ${ms}ms)"
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
