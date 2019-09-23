package bloop.engine.caches

import bloop.Compiler
import bloop.CompilerOracle
import bloop.CompileProducts
import bloop.data.Project
import bloop.io.AbsolutePath

import java.nio.file.Files
import java.util.Optional

import xsbti.compile.{PreviousResult, CompileAnalysis, MiniSetup, FileHash}

import monix.eval.Task
import bloop.UniqueCompileInputs
import bloop.CompileOutPaths
import monix.execution.atomic.AtomicInt

case class LastSuccessfulResult(
    sources: Vector[UniqueCompileInputs.HashedSource],
    classpath: Vector[FileHash],
    previous: PreviousResult,
    classesDir: AbsolutePath,
    counterForClassesDir: AtomicInt,
    populatingProducts: Task[Unit]
) {
  def isEmpty: Boolean = {
    sources.isEmpty &&
    classpath.isEmpty &&
    previous == LastSuccessfulResult.EmptyPreviousResult &&
    CompileOutPaths.hasEmptyClassesDir(classesDir)
  }
}

object LastSuccessfulResult {
  private final val EmptyPreviousResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])

  def empty(project: Project): LastSuccessfulResult = {
    val emptyClassesDir =
      CompileOutPaths.deriveEmptyClassesDir(project.name, project.genericClassesDir)
    LastSuccessfulResult(
      Vector.empty,
      Vector.empty,
      EmptyPreviousResult,
      emptyClassesDir,
      AtomicInt(0),
      Task.now(())
    )
  }

  def apply(
      inputs: UniqueCompileInputs,
      products: CompileProducts,
      backgroundIO: Task[Unit]
  ): LastSuccessfulResult = {
    LastSuccessfulResult(
      inputs.sources,
      inputs.classpath,
      products.resultForFutureCompilationRuns,
      AbsolutePath(products.newClassesDir),
      AtomicInt(0),
      backgroundIO
    )
  }
}
