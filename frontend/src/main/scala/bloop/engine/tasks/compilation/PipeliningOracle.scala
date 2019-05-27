package bloop.engine.tasks.compilation

import java.io.File

import bloop.data.Project
import bloop.{Compiler, CompilerOracle}
import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import bloop.ScalaSig

import scala.concurrent.Promise

import monix.eval.Task
import bloop.logging.Logger
import bloop.tracing.BraveTracer
import xsbti.compile.Signature

/** @inheritdoc */
final class PipeliningOracle(
    bundle: CompileBundle,
    signaturesFromRunningCompilations: Array[Signature],
    startDownstreamCompilation: Promise[Array[Signature]],
    scheduledCompilations: List[PartialSuccess]
) extends CompilerOracle {

  /** @inheritdoc */
  override def askForJavaSourcesOfIncompleteCompilations: List[File] = {
    scheduledCompilations.flatMap { r =>
      r.pipeliningResults match {
        case None => Nil
        case Some(results) =>
          if (results.isJavaCompilationFinished.isCompleted) Nil
          else r.bundle.javaSources.map(_.toFile)
      }
    }
  }

  /** @inheritdoc */
  def registerDefinedMacro(definedMacroSymbol: String): Unit = ()

  /** @inheritdoc */
  def blockUntilMacroClasspathIsReady(usedMacroSymbol: String): Unit = ()

  /** @inheritdoc */
  def isPipeliningEnabled: Boolean = !startDownstreamCompilation.isCompleted

  /** @inheritdoc */
  def startDownstreamCompilations(picklesDir: AbsolutePath, signatures: Array[Signature]): Unit = {
    startDownstreamCompilation.success(signatures)
  }

  /** @inheritdoc */
  def collectDownstreamSignatures(): Array[Signature] = signaturesFromRunningCompilations
}

object ImmutableCompilerOracle {

  /**
   * Persists in-memory signatures to a pickles directory associated with the
   * target that producted them.
   *
   * For the moment, this logic is unused in favor of an in-memory populating
   * strategy via the analysis callback endpoint `downstreamSignatures`.
   */
  def writeSignaturesToPicklesDir(
      picklesDir: AbsolutePath,
      signatures: List[Signature],
      startDownstreamCompilation: Promise[Unit],
      tracer: BraveTracer,
      logger: Logger
  ): Unit = {
    val writePickles = signatures.map(ScalaSig.write(picklesDir, _, logger))
    val groupTasks = writePickles.grouped(4).map(group => Task.gatherUnordered(group)).toList
    val persistPicklesInParallel = {
      tracer.traceTask("writing pickles") { _ =>
        Task.sequence(groupTasks).doOnFinish {
          case None => Task.now { startDownstreamCompilation.trySuccess(()); () }
          case Some(t) => Task.now { startDownstreamCompilation.tryFailure(t); () }
        }
      }
    }

    // Think strategies to get a hold of this future or cancel it if compilation is cancelled
    persistPicklesInParallel.runAsync(ExecutionContext.ioScheduler)
    ()
  }
}
