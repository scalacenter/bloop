package bloop.engine.caches

import java.util.Optional

import bloop.CompileOutPaths
import bloop.CompileProducts
import bloop.UniqueCompileInputs
import bloop.data.Project
import bloop.io.AbsolutePath
import bloop.task.Task

import monix.execution.atomic.AtomicInt
import xsbti.compile.CompileAnalysis
import xsbti.compile.FileHash
import xsbti.compile.MiniSetup
import xsbti.compile.PreviousResult

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

  override def toString: String = {
    pprint.apply(this, height = Int.MaxValue).render
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
