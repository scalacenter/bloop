package bloop.engine.caches

import java.util.Optional
import java.nio.file.Files

import bloop.{Compiler, CompileProducts}
import bloop.data.Project
import bloop.Compiler.Result
import bloop.engine.tasks.compilation.{
  FinalCompileResult,
  FinalEmptyResult,
  FinalNormalCompileResult,
  ResultBundle
}
import bloop.engine.{Build, ExecutionContext}
import bloop.io.AbsolutePath
import bloop.logging.{DebugFilter, Logger, ObservedLogger}
import bloop.reporter.{LogReporter, ReporterConfig}

import monix.eval.Task
import monix.execution.CancelableFuture

import sbt.internal.inc.FileAnalysisStore
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
 * Maps projects to compilation results, populated by `Tasks.compile`.
 *
 * The results cache has two important goals:
 *   1. Keep track of the last compilation results, no matter what results those were.
 *   2. Keep track of the last successful incremental results per project, so that they
 *      can be used by `compile` to locate the last good analysis file.
 *
 * This data structure is not thread-safe and should not be used like so.
 *
 * @param all The map of projects to latest compilation results.
 * @param successful The map of all projects to latest successful compilation results.
 */
final case class ResultsCache private (
    all: Map[Project, Compiler.Result],
    successful: Map[Project, LastSuccessfulResult]
) {

  /** Returns the last succesful result if present, empty otherwise. */
  def lastSuccessfulResultOrEmpty(project: Project): LastSuccessfulResult =
    lastSuccessfulResult(project).getOrElse(LastSuccessfulResult.empty(project))

  /** Returns an optional last succesful result. */
  private[bloop] def lastSuccessfulResult(project: Project): Option[LastSuccessfulResult] =
    successful.get(project)

  /** Returns the latest compilation result if present, empty otherwise. */
  def latestResult(project: Project): Compiler.Result =
    all.getOrElse(project, Result.Empty)

  /** Diff the latest changed projects between `cache` and `this`. */
  def diffLatest(cache: ResultsCache): List[(Project, Compiler.Result)] = {
    cache.allResults.collect { case t @ (p, r) if this.latestResult(p) != r => t }.toList
  }

  def allResults: Iterator[(Project, Compiler.Result)] = all.iterator
  def allSuccessful: Iterator[(Project, LastSuccessfulResult)] = successful.iterator
  def cleanSuccessful(projects: List[Project]): ResultsCache = {
    // Remove all the successful results from the cache.
    val newSuccessful = successful.filterKeys(p => !projects.contains(p))
    val newAll = all.filterKeys(p => !projects.contains(p))
    new ResultsCache(newAll, newSuccessful)
  }

  def addResult(
      project: Project,
      results: ResultBundle
  ): ResultsCache = {
    val newAll = all + (project -> results.fromCompiler)
    results.successful match {
      case Some(newSuccessful) =>
        new ResultsCache(newAll, successful + (project -> newSuccessful))
      case None => new ResultsCache(newAll, successful)
    }
  }

  def addFinalResults(ps: List[FinalCompileResult]): ResultsCache = {
    ps.foldLeft(this) {
      case (rs, FinalNormalCompileResult(p, r, _)) => rs.addResult(p, r)
      case (rs, FinalEmptyResult) => rs
    }
  }

  def replacePreviousResults(ps: Map[Project, Compiler.Result]): ResultsCache = {
    ps.foldLeft(this) {
      case (rs, (project, result)) =>
        val newAll = rs.all + (project -> result)
        new ResultsCache(newAll, rs.successful)
    }
  }

  override def toString: String = pprint.apply(this, height = Int.MaxValue).render
}

object ResultsCache {
  private implicit val logContext: DebugFilter = DebugFilter.All
  private[bloop] val emptyForTests: ResultsCache =
    new ResultsCache(Map.empty, Map.empty)

  def load(build: Build, cwd: AbsolutePath, logger: Logger): ResultsCache = {
    val handle = loadAsync(build, cwd, logger).runToFuture(ExecutionContext.ioScheduler)
    Await.result(handle, Duration.Inf)
  }

  def loadAsync(build: Build, cwd: AbsolutePath, logger: Logger): Task[ResultsCache] = {
    import bloop.util.JavaCompat.EnrichOptional

    def fetchPreviousResult(p: Project): Task[ResultBundle] = {
      val analysisFile = p.analysisOut
      if (analysisFile.exists) {
        Task {
          val contents = FileAnalysisStore.binary(analysisFile.toFile).get().toOption
          contents match {
            case Some(res) =>
              import monix.execution.CancelableFuture
              logger.debug(s"Loading previous analysis for '${p.name}' from '$analysisFile'.")
              val r = PreviousResult.of(Optional.of(res.getAnalysis), Optional.of(res.getMiniSetup))
              res.getAnalysis.readCompilations.getAllCompilations.lastOption match {
                case Some(lastCompilation) =>
                  lastCompilation.getOutput.getSingleOutput.toOption match {
                    case Some(classesDirFile) =>
                      val classesDir = classesDirFile.toPath
                      val originPath = p.origin.path.syntax
                      val originHash = p.origin.hash
                      val inputs = bloop.CompilerOracle.Inputs.emptyFor(originPath, originHash)
                      import bloop.CompileBackgroundTasks
                      val dummyTasks = CompileBackgroundTasks.empty
                      val dummy = ObservedLogger.dummy(logger, ExecutionContext.ioScheduler)
                      val reporter = new LogReporter(p, dummy, cwd, ReporterConfig.defaultFormat)
                      val products = CompileProducts(classesDir, classesDir, r, r, Set.empty)
                      ResultBundle(
                        Result.Success(inputs, reporter, products, 0L, dummyTasks, false),
                        Some(LastSuccessfulResult(inputs, products, Task.now(()))),
                        CancelableFuture.successful(())
                      )
                    case None =>
                      logger.debug(
                        s"Analysis '$analysisFile' last compilation for '${p.name}' didn't contain classes dir."
                      )
                      ResultBundle.empty
                  }
                case None =>
                  logger.debug(
                    s"Analysis '$analysisFile' for '${p.name}' didn't contain last compilation."
                  )
                  ResultBundle.empty
              }
            case None =>
              logger.debug(s"Analysis '$analysisFile' for '${p.name}' is empty.")
              ResultBundle.empty
          }

        }
      } else {
        Task.now {
          logger.debug(s"Missing analysis file for project '${p.name}'")
          ResultBundle.empty
        }
      }
    }

    val all = build.projects.map(p => fetchPreviousResult(p).map(r => p -> r))
    Task.gatherUnordered(all).executeOn(ExecutionContext.ioScheduler).map { projectResults =>
      val cache = new ResultsCache(Map.empty, Map.empty)
      projectResults.foldLeft(cache) {
        case (rs, (p, r)) => rs.addResult(p, r)
      }
    }
  }
}
