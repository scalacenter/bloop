package bloop.engine.caches

import java.util.Optional

import bloop.Compiler
import bloop.data.Project
import bloop.Compiler.Result
import bloop.engine.tasks.compilation.{
  FinalCompileResult,
  FinalEmptyResult,
  FinalNormalCompileResult
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
final class ResultsCache private (
    all: Map[Project, Compiler.Result],
    successful: Map[Project, LastSuccessfulResult]
) {

  /** Returns the last succesful result if present, empty otherwise. */
  def lastSuccessfulResultOrEmpty(project: Project): LastSuccessfulResult =
    lastSuccessfulResult(project).getOrElse(LastSuccessfulResult.Empty)

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
    new ResultsCache(all, newSuccessful)
  }

  def addResult(
      project: Project,
      result: Compiler.Result,
      populateNewClassesDir: CancelableFuture[Unit]
  ): ResultsCache = {
    val newAll = all + (project -> result)
    result match {
      case s: Compiler.Result.Success =>
        val newSuccessful = LastSuccessfulResult(s.products, populateNewClassesDir)
        new ResultsCache(newAll, successful + (project -> newSuccessful))
      case Compiler.Result.Empty =>
        new ResultsCache(newAll, successful + (project -> LastSuccessfulResult.Empty))
      case r => new ResultsCache(newAll, successful)
    }
  }

  def addFinalResults(ps: List[FinalCompileResult]): ResultsCache = {
    ps.foldLeft(this) {
      case (rs, FinalNormalCompileResult(p, r, _, io)) => rs.addResult(p, r, io)
      case (rs, FinalEmptyResult) => rs
    }
  }

  override def toString: String = {
    s"""ResultsCache(
       |  all = ${all.mkString(", ")}
       |
       |  successful = ${successful.mkString(", ")}
       |)
     """.stripMargin
  }
}

object ResultsCache {
  import java.util.concurrent.ConcurrentHashMap

  private implicit val logContext: DebugFilter = DebugFilter.All

  private[bloop] val emptyForTests: ResultsCache =
    new ResultsCache(Map.empty, Map.empty)

  def load(build: Build, cwd: AbsolutePath, logger: Logger): ResultsCache = {
    val handle = loadAsync(build, cwd, logger).runAsync(ExecutionContext.ioScheduler)
    Await.result(handle, Duration.Inf)
  }

  def loadAsync(build: Build, cwd: AbsolutePath, logger: Logger): Task[ResultsCache] = {
    import bloop.util.JavaCompat.EnrichOptional

    def fetchPreviousResult(p: Project): Task[Compiler.Result] = {
      val analysisFile = p.analysisOut
      if (analysisFile.exists) {
        Task {
          val contents = FileAnalysisStore.binary(analysisFile.toFile).get().toOption
          contents match {
            case Some(res) =>
              import monix.execution.CancelableFuture
              logger.debug(s"Loading previous analysis for '${p.name}' from '$analysisFile'.")
              val classesDir = p.classesDir.underlying
              val r = PreviousResult.of(Optional.of(res.getAnalysis), Optional.of(res.getMiniSetup))
              val dummy = ObservedLogger.dummy(logger, ExecutionContext.ioScheduler)
              val reporter = new LogReporter(p, dummy, cwd, ReporterConfig.defaultFormat)
              val products = bloop.CompileProducts(classesDir, classesDir, r, r, Set.empty)
              Result.Success(reporter, products, 0L, CancelableFuture.successful(()))
            case None =>
              logger.debug(s"Analysis '$analysisFile' for '${p.name}' is empty.")
              Result.Empty
          }

        }
      } else {
        Task.now {
          logger.debug(s"Missing analysis file for project '${p.name}'")
          Result.Empty
        }
      }
    }

    val all = build.projects.map(p => fetchPreviousResult(p).map(r => p -> r))
    Task.gatherUnordered(all).executeOn(ExecutionContext.ioScheduler).map { projectResults =>
      val cache = new ResultsCache(Map.empty, Map.empty)
      projectResults.foldLeft(cache) {
        case (rs, (p, r)) => rs.addResult(p, r, CancelableFuture.successful(()))
      }
    }
  }
}
