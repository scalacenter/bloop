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
  def result: R
}

sealed trait PartialCompileResult extends CompileResult[Task[Compiler.Result]] {
  def javaSources: List[String]
  def result: Task[Compiler.Result]
}

object PartialCompileResult {
  def apply(
      project: Project,
      pickleURI: Try[Optional[URI]],
      javaSources: List[String],
      completeJava: Task[JavaSignal],
      result: Task[Compiler.Result]
  ): PartialCompileResult = {
    pickleURI match {
      case scala.util.Success(opt) =>
        PartialSuccess(project, opt, javaSources, completeJava, result)
      case scala.util.Failure(CompileExceptions.CompletePromise) =>
        PartialSuccess(project, Optional.empty(), javaSources, completeJava, result)
      case scala.util.Failure(t) =>
        PartialFailure(project, t, javaSources, result)
    }
  }

  def toFinalResult(result: PartialCompileResult): Task[List[FinalCompileResult]] = {
    result match {
      case PartialEmpty => Task.now(FinalEmptyResult :: Nil)
      case PartialFailure(project, _, _, result) =>
        result.map(res => FinalNormalCompileResult(project, res) :: Nil)
      case PartialFailures(failures, result) =>
        Task.gatherUnordered(failures.map(toFinalResult(_))).map(_.flatten)
      case PartialSuccess(project, _, _, _, result) =>
        result.map(res => FinalNormalCompileResult(project, res) :: Nil)
    }
  }
}

case object PartialEmpty extends PartialCompileResult {
  override final val javaSources: List[String] = Nil
  override final val result: Task[Compiler.Result] = Task.now(Compiler.Result.Empty)
}

case class PartialFailure(
    project: Project,
    exception: Throwable,
    javaSources: List[String],
    result: Task[Compiler.Result]
) extends PartialCompileResult
    with CacheHashCode

case class PartialFailures(
    failures: List[PartialCompileResult],
    result: Task[Compiler.Result]
) extends PartialCompileResult
    with CacheHashCode {
  override final val javaSources: List[String] = Nil
}

case class PartialSuccess(
    project: Project,
    pickleURI: Optional[URI],
    javaSources: List[String],
    completeJava: Task[JavaSignal],
    result: Task[Compiler.Result]
) extends PartialCompileResult
    with CacheHashCode

sealed trait FinalCompileResult extends CompileResult[Compiler.Result] {
  def result: Compiler.Result
}

case object FinalEmptyResult extends FinalCompileResult {
  final val result = Compiler.Result.Empty
}

case class FinalNormalCompileResult private (
    project: Project,
    result: Compiler.Result
) extends FinalCompileResult
    with CacheHashCode

object FinalCompileResult {
  import scalaz.Show
  final implicit val showFinalResult: Show[FinalCompileResult] = new Show[FinalCompileResult] {
    private def seconds(ms: Double): String = s"${ms}ms"
    override def shows(r: FinalCompileResult): String = {
      r match {
        case FinalEmptyResult => s"<empty> (product of dag aggregation)"
        case FinalNormalCompileResult(project, result) =>
          val projectName = project.name
          result match {
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

}
