package bloop.engine.tasks.compilation

import bloop.Compiler
import bloop.engine.caches.LastSuccessfulResult

import monix.execution.CancelableFuture
import monix.eval.Task

/**
 * Defines a result that aggregates several compilation outputs together.
 *
 * @param fromCompiler The compiler result we got directly from Zinc compiler APIs.
 * @param successful The result created when a compilation is successful,
 *   empty otherwise. When successful, it will be used by future compilations.
 * @param previous The last successful result used as the basis to trigger a
 *   new compile. If the compile is successful it'll produce [[successful]].
 * @param runningBackgroundTasks Tasks running in the background and that must
 *   be blocked on for compiler correctness reasons.
 */
case class ResultBundle(
    fromCompiler: Compiler.Result,
    successful: Option[LastSuccessfulResult],
    previous: Option[LastSuccessfulResult],
    runningBackgroundTasks: CancelableFuture[Unit]
)

object ResultBundle {
  val empty: ResultBundle = {
    ResultBundle(Compiler.Result.Empty, None, None, CancelableFuture.unit)
  }

  def apply(
      fromCompiler: Compiler.Result,
      successful: Option[LastSuccessfulResult],
      previous: Option[LastSuccessfulResult]
  ): ResultBundle = ResultBundle(fromCompiler, successful, previous, CancelableFuture.unit)
}
