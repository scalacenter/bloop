package bloop.engine.tasks.compilation

import bloop.Compiler
import bloop.engine.caches.LastSuccessfulResult

import monix.execution.CancelableFuture
import monix.eval.Task

case class ResultBundle(
    fromCompiler: Compiler.Result,
    successful: Option[LastSuccessfulResult],
    runningBackgroundTasks: CancelableFuture[Unit],
    deletePickleDirTask: Task[Unit]
)

object ResultBundle {
  val empty: ResultBundle =
    ResultBundle(Compiler.Result.Empty, None, CancelableFuture.successful(()), Task.unit)

  def apply(
      fromCompiler: Compiler.Result,
      successful: Option[LastSuccessfulResult]
  ): ResultBundle = ResultBundle(fromCompiler, successful, CancelableFuture.unit, Task.unit)
}
