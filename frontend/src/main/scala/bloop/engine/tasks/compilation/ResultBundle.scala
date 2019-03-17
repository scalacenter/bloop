package bloop.engine.tasks.compilation

import bloop.Compiler
import bloop.engine.caches.LastSuccessfulResult

case class ResultBundle(
    fromCompiler: Compiler.Result,
    successful: Option[LastSuccessfulResult]
)

object ResultBundle {
  val empty: ResultBundle = ResultBundle(Compiler.Result.Empty, None)
}
