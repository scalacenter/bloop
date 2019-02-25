package bloop.engine.caches

import bloop.Compiler
import bloop.CompileProducts
import bloop.io.AbsolutePath

import java.nio.file.Files
import java.util.Optional

import xsbti.compile.{PreviousResult, CompileAnalysis, MiniSetup}

import monix.eval.Task

case class LastSuccessfulResult(
    previous: PreviousResult,
    classesDir: AbsolutePath,
    populatingProducts: Task[Unit]
)

object LastSuccessfulResult {
  final val Empty: LastSuccessfulResult = LastSuccessfulResult(
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup]),
    AbsolutePath(Files.createTempDirectory("empty-result").toRealPath()),
    Task.now(()).memoize
  )

  def apply(products: CompileProducts): LastSuccessfulResult = {
    LastSuccessfulResult(
      products.resultForFutureCompilationRuns,
      AbsolutePath(products.newClassesDir),
      Task.now(())
    )
  }
}
