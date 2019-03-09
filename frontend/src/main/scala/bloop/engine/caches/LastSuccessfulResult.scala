package bloop.engine.caches

import bloop.Compiler
import bloop.CompileProducts
import bloop.data.Project
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
  private final val EmptyPreviousResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])

  final def empty(project: Project): LastSuccessfulResult = {
    val classesDir = Files.createDirectories(
      project.genericClassesDir.getParent.resolve(s"empty-${project.name}").underlying
    )
    LastSuccessfulResult(
      EmptyPreviousResult,
      AbsolutePath(classesDir.toRealPath()),
      CancelableFuture.successful(())
    )
  }

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
