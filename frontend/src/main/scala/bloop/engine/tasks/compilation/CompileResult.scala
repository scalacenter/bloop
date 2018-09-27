package bloop.engine.tasks.compilation

import java.util.concurrent.CompletableFuture

import bloop.{Compiler, JavaSignal}
import bloop.reporter.Problem
import bloop.util.CacheHashCode
import monix.eval.Task
import xsbti.compile.{EmptyIRStore, IRStore}

import scala.util.Try

sealed trait CompileResult[+R] {
  def result: R
}

sealed trait PartialCompileResult extends CompileResult[Task[Compiler.Result]] {
  def result: Task[Compiler.Result]
  def store: IRStore
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

  def toFinalResult(result: PartialCompileResult): Task[List[FinalCompileResult]] = {
    result match {
      case PartialEmpty => Task.now(FinalEmptyResult :: Nil)
      case f @ PartialFailure(project, _, result) =>
        result.map(res => FinalNormalCompileResult(project, res, f.store) :: Nil)
      case PartialFailures(failures, result) =>
        Task.gatherUnordered(failures.map(toFinalResult(_))).map(_.flatten)
      case PartialSuccess(project, store, _, _, result) =>
        result.map(res => FinalNormalCompileResult(project, res, store) :: Nil)
    }
  }
}

case object PartialEmpty extends PartialCompileResult {
  override final val result: Task[Compiler.Result] = Task.now(Compiler.Result.Empty)
  override def store: IRStore = EmptyIRStore.getStore()
}

case class PartialFailure(
    bundle: CompileBundle,
    exception: Throwable,
    result: Task[Compiler.Result]
) extends PartialCompileResult
    with CacheHashCode {
  def store: IRStore = EmptyIRStore.getStore()
}

case class PartialFailures(
    failures: List[PartialCompileResult],
    result: Task[Compiler.Result]
) extends PartialCompileResult
    with CacheHashCode {
  override def store: IRStore = EmptyIRStore.getStore()
}

case class PartialSuccess(
    bundle: CompileBundle,
    store: IRStore,
    completeJava: CompletableFuture[Unit],
    javaTrigger: Task[JavaSignal],
    result: Task[Compiler.Result]
) extends PartialCompileResult
    with CacheHashCode

sealed trait FinalCompileResult extends CompileResult[Compiler.Result] {
  def store: IRStore
  def result: Compiler.Result
}

case object FinalEmptyResult extends FinalCompileResult {
  override final val store: IRStore = EmptyIRStore.getStore()
  override final val result: Compiler.Result = Compiler.Result.Empty
}

case class FinalNormalCompileResult private (
    bundle: CompileBundle,
    result: Compiler.Result,
    store: IRStore
) extends FinalCompileResult
    with CacheHashCode

object FinalCompileResult {
  import scalaz.Show
  final implicit val showFinalResult: Show[FinalCompileResult] = new Show[FinalCompileResult] {
    private def seconds(ms: Double): String = s"${ms}ms"
    override def shows(r: FinalCompileResult): String = {
      r match {
        case FinalEmptyResult => s"<empty> (product of dag aggregation)"
        case FinalNormalCompileResult(bundle, result, _) =>
          val projectName = bundle.project.name
          result match {
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
}
