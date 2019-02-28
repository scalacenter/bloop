package bloop.engine.caches

import bloop.Compiler
import bloop.CompileProducts
import bloop.io.AbsolutePath

import java.nio.file.Files
import java.util.Optional

import xsbti.compile.{PreviousResult, CompileAnalysis, MiniSetup}

import monix.execution.CancelableFuture

case class LastSuccessfulResult(
    previous: PreviousResult,
    classesDir: AbsolutePath,
    populatingProducts: CancelableFuture[Unit]
)

object LastSuccessfulResult {
  final val Empty: LastSuccessfulResult = LastSuccessfulResult(
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup]),
    AbsolutePath(Files.createTempDirectory("empty-last-successful").toRealPath()),
    CancelableFuture.successful(())
  )

  def apply(
      products: CompileProducts,
      backgroundIO: CancelableFuture[Unit]
  ): LastSuccessfulResult = {
    LastSuccessfulResult(
      products.resultForFutureCompilationRuns,
      AbsolutePath(products.newClassesDir),
      backgroundIO
    )
  }
}
