package bloop.engine.tasks.compilation

import java.io.File

import bloop.data.Project
import bloop.{Compiler, CompilerOracle}
import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import bloop.ScalaSig
import bloop.logging.Logger
import bloop.tracing.BraveTracer

import scala.concurrent.Promise
import scala.collection.mutable

import monix.eval.Task
import xsbti.compile.Signature
import monix.execution.atomic.AtomicBoolean
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import monix.execution.misc.NonFatal

/** @inheritdoc */
final class PipeliningOracle(
    bundle: CompileBundle,
    signaturesFromRunningCompilations: Array[Signature],
    definedMacrosFromRunningCompilations: Map[Project, Array[String]],
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

  private val definedMacros = new mutable.HashSet[String]()

  /** @inheritdoc */
  def registerDefinedMacro(definedMacroSymbol: String): Unit = definedMacros.+=(definedMacroSymbol)

  /** @inheritdoc */
  def collectDefinedMacroSymbols: Array[String] = definedMacros.toArray

  /** @inheritdoc */
  @volatile private var requiresMacroInitialization: Boolean = false
  def blockUntilMacroClasspathIsReady(usedMacroSymbol: String): Unit = {
    if (requiresMacroInitialization) ()
    else {
      val noMacrosDefinedInDependentProjects = {
        definedMacrosFromRunningCompilations.isEmpty ||
        definedMacrosFromRunningCompilations.forall(_._2.isEmpty)
      }

      if (noMacrosDefinedInDependentProjects) {
        requiresMacroInitialization = true
      } else {
        // Only return promises for those projects that define any macros
        val dependentProjectPromises = scheduledCompilations.flatMap { r =>
          r.pipeliningResults match {
            case None => Nil
            case Some(results) =>
              val hasNoMacros = {
                val macros = definedMacrosFromRunningCompilations.get(r.bundle.project)
                macros.isEmpty || macros.exists(_.isEmpty)
              }
              if (hasNoMacros) Nil
              else List(Task.deferFuture(results.productsWhenCompilationIsFinished.future))
          }
        }

        val waitDownstreamFullCompilations = {
          Task
            .sequence(dependentProjectPromises)
            .map(_ => ())
            .runAsync(ExecutionContext.ioScheduler)
        }

        /**
         * Block until all the downstream compilations have completed.
         *
         * We have a guarantee from bloop that these promises will be always
         * completed even if their associated compilations fail or are
         * cancelled. In any of this scenario, and even if we throw on this
         * wait, we catch it and let the compiler logic handle it. If the user
         * has cancelled this compilation as well, the compiler logic will
         * exit. If the compilation downstream failed, this compilation will
         * fail too because supposedly it accesses macros defined downstream.
         * Failing here it's fine.
         */
        try Await.result(waitDownstreamFullCompilations, Duration.Inf)
        catch { case NonFatal(e) => () } finally {
          requiresMacroInitialization = true
        }
      }
    }
  }

  /** @inheritdoc */
  def isPipeliningEnabled: Boolean = !startDownstreamCompilation.isCompleted

  /** @inheritdoc */
  def startDownstreamCompilations(signatures: Array[Signature]): Unit = {
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
