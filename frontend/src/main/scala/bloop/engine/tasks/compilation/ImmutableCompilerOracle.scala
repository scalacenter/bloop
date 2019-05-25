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

/** @inheritdoc */
final class ImmutableCompilerOracle(
    project: Project,
    startDownstreamCompilation: Promise[Unit],
    scheduledCompilations: List[PartialSuccess],
    tracer: BraveTracer,
    logger: Logger
) extends CompilerOracle {

  /** @inheritdoc */
  override def askForJavaSourcesOfIncompleteCompilations: List[File] = {
    scheduledCompilations.flatMap { r =>
      val runningPromise = r.completeJava
      if (runningPromise.isCompleted) Nil
      else r.bundle.javaSources.map(_.toFile)
    }
  }

  /** @inheritdoc */
  def registerDefinedMacro(definedMacroSymbol: String): Unit = ()

  /** @inheritdoc */
  def blockUntilMacroClasspathIsReady(usedMacroSymbol: String): Unit = ()

  /** @inheritdoc */
  def startDownstreamCompilations(
      picklesDir: AbsolutePath,
      pickles: List[ScalaSig]
  ): Unit = {
    val writePickles = pickles.map(ScalaSig.write(picklesDir, _, logger))
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
