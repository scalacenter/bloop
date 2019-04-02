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

case class LastSuccessfulResult(
    sources: Vector[CompilerOracle.HashedSource],
    classpath: Vector[FileHash],
    previous: PreviousResult,
    classesDir: AbsolutePath,
    populatingProducts: Task[Unit]
)

object LastSuccessfulResult {
  private final val EmptyPreviousResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])

  final def empty(project: Project): LastSuccessfulResult = {
    val classesDir = Files.createDirectories(
      project.genericClassesDir.getParent.resolve(s"classes-empty-${project.name}").underlying
    )
    LastSuccessfulResult(
      Vector.empty,
      Vector.empty,
      EmptyPreviousResult,
      AbsolutePath(classesDir.toRealPath()),
      Task.now(())
    )
  }

  def apply(
      inputs: CompilerOracle.Inputs,
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
