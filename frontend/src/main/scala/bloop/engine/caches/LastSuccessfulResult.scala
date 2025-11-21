package bloop.engine.caches

import java.util.Optional

import bloop.CompileOutPaths
import bloop.CompileProducts
import bloop.UniqueCompileInputs
import bloop.data.Project
import bloop.io.AbsolutePath
import bloop.task.Task

import xsbti.compile.CompileAnalysis
import xsbti.compile.MiniSetup
import xsbti.compile.PreviousResult

case class LastSuccessfulResult(
    noClassPathAndSources: Boolean,
    previous: PreviousResult,
    classesDir: AbsolutePath,
    populatingProducts: Task[Unit]
) {
  def isEmpty: Boolean = {
    noClassPathAndSources &&
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
      noClassPathAndSources = true,
      EmptyPreviousResult,
      emptyClassesDir,
      Task.now(())
    )
  }

  def apply(
      inputs: UniqueCompileInputs,
      products: CompileProducts,
      backgroundIO: Task[Unit]
  ): LastSuccessfulResult = {
    LastSuccessfulResult(
      noClassPathAndSources = inputs.sources.size == 0 && inputs.classpath.size == 0,
      products.resultForFutureCompilationRuns,
      AbsolutePath(products.newClassesDir),
      backgroundIO
    )
  }
}
