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

case class LastSuccessfulResult(
    sources: Vector[UniqueCompileInputs.HashedSource],
    classpath: Vector[FileHash],
    previous: PreviousResult,
    classesDir: AbsolutePath,
    populatingProducts: Task[Unit]
) {
  def hasEmptyClassesDir: Boolean =
    classesDir.underlying.getFileName().toString.startsWith("classes-empty-")
}

object LastSuccessfulResult {
  private final val EmptyPreviousResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])

  def empty(project: Project): LastSuccessfulResult = {
    /*
     * An empty classes directory never exists on purpose. It is merely a
     * placeholder until a non empty classes directory is used. There is only
     * one single empty classes directory per project and can be shared by
     * different projects, so to avoid problems across different compilations
     * we never create this directory and special case Zinc logic to skip it.
     *
     * The prefix name 'classes-empty-` of this classes directory should not
     * change without modifying `BloopLookup` defined in `backend`.
     */
    val classesDirName = s"classes-empty-${project.name}"
    val classesDir = project.genericClassesDir.getParent.resolve(classesDirName).underlying
    LastSuccessfulResult(
      Vector.empty,
      Vector.empty,
      EmptyPreviousResult,
      AbsolutePath(classesDir),
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
      backgroundIO
    )
  }
}
