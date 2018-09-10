package bloop.engine.tasks.compilation

import java.net.URI
import java.util.Optional

import bloop.{Compiler, Project}
import bloop.reporter.Problem
import bloop.util.CacheHashCode
import monix.eval.Task

import scala.util.Try

sealed trait CompileResult[+R] {
  def project: Project
  def result: R
}

sealed trait PartialCompileResult extends CompileResult[Task[Compiler.Result]] {
  def project: Project
  def javaSources: List[String]
  def result: Task[Compiler.Result]

  def toFinalResult: Task[FinalCompileResult] =
    result.map(res => FinalCompileResult(project, res))
}

object PartialCompileResult {
  def apply(
      project: Project,
      pickleURI: Try[Optional[URI]],
      javaSources: List[String],
      result: Task[Compiler.Result]): PartialCompileResult = {
    pickleURI match {
      case scala.util.Success(opt) =>
        PartialSuccess(project, opt, javaSources, result)
      case scala.util.Failure(CompileExceptions.CompletePromise) =>
        PartialSuccess(project, Optional.empty(), javaSources, result)
      case scala.util.Failure(t) =>
        PartialFailure(project, t, javaSources, result)
    }
  }
}

case class PartialFailure(
    project: Project,
    exception: Throwable,
    javaSources: List[String],
    result: Task[Compiler.Result]
) extends PartialCompileResult
    with CacheHashCode

case class PartialSuccess(
    project: Project,
    pickleURI: Optional[URI],
    javaSources: List[String],
    result: Task[Compiler.Result]
) extends PartialCompileResult
    with CacheHashCode

case class FinalCompileResult(
    project: Project,
    result: Compiler.Result
) extends CompileResult[Compiler.Result]
    with CacheHashCode

object FinalCompileResult {
  import scalaz.Show
  final implicit val showFinalResult: Show[FinalCompileResult] = new Show[FinalCompileResult] {
    private def seconds(ms: Double): String = s"${ms}ms"
    override def shows(r: FinalCompileResult): String = {
      val projectName = r.project.name
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
