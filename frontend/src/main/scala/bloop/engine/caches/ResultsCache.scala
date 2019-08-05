package bloop.engine.caches

import java.util.Optional
import java.nio.file.Files

import bloop.{Compiler, CompileProducts}
import bloop.data.{Project, ClientInfo}
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
import bloop.UniqueCompileInputs
import bloop.CompileOutPaths
import scala.collection.mutable
import java.nio.file.Path
import bloop.io.Paths
import java.nio.file.NoSuchFileException
import monix.execution.misc.NonFatal
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

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
      case (rs, FinalNormalCompileResult(p, r)) => rs.addResult(p, r)
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

  private final val cleanedOrphanDirsInBuild = new ConcurrentHashMap[AbsolutePath, Boolean]()

  def load(
      build: Build,
      cwd: AbsolutePath,
      cleanOrphanedInternalDirs: Boolean,
      logger: Logger
  ): ResultsCache = {
    val handle = loadAsync(build, cwd, cleanOrphanedInternalDirs, logger)
    Await.result(handle.runAsync(ExecutionContext.ioScheduler), Duration.Inf)
  }

  def loadAsync(
      build: Build,
      cwd: AbsolutePath,
      cleanOrphanedInternalDirs: Boolean,
      logger: Logger
  ): Task[ResultsCache] = {
    import bloop.util.JavaCompat.EnrichOptional

    def cleanUpOrphanedInternalDirs(
        project: Project,
        analysisClassesDir: AbsolutePath
    ): Task[Unit] = {
      if (cleanOrphanedInternalDirs) Task.unit
      else {
        val internalClassesDir =
          CompileOutPaths.createInternalClassesRootDir(project.genericClassesDir)
        // This is a surprise, skip any cleanup if internal analysis dir doesn't
        // live under the internal classes dir root assigned to the project
        if (internalClassesDir != analysisClassesDir.getParent) Task.unit
        else {
          ClientInfo.toGenericClassesDir(analysisClassesDir) match {
            case Some(genericClassesName) =>
              val deleteOrphans = Task {
                import scala.collection.JavaConverters._
                val orphanInternalDirs = new mutable.ListBuffer[Path]()
                Paths.list(internalClassesDir).foreach { absPath =>
                  val path = absPath.underlying
                  val fileName = path.getFileName().toString
                  /*
                   * An internal classes directory is orphan if it's mapped to
                   * the same project and it's not the analysis classes
                   * directory from which we're loading the compile analysis.
                   */
                  val isOrphan =
                    fileName.startsWith(genericClassesName) &&
                      path != analysisClassesDir.underlying
                  if (isOrphan) {
                    orphanInternalDirs.+=(path)
                  }
                }

                orphanInternalDirs.foreach { orphanDir =>
                  try {
                    Paths.delete(AbsolutePath(orphanDir))
                  } catch {
                    case _: NoSuchFileException => ()
                    case NonFatal(t) =>
                      logger.debug(
                        s"Unexpected error when pruning internal classes dir $orphanDir"
                      )(DebugFilter.All)
                      logger.trace(t)
                  }
                }
              }
              deleteOrphans.materialize.map(_ => ())
            case None => Task.unit
          }
        }
      }
    }

    def fetchPreviousResult(p: Project): Task[(ResultBundle, Option[Task[Unit]])] = {
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
                      val inputs = UniqueCompileInputs.emptyFor(originPath)
                      val dummyTasks = bloop.CompileBackgroundTasks.empty
                      val dummy = ObservedLogger.dummy(logger, ExecutionContext.ioScheduler)
                      val reporter = new LogReporter(p, dummy, cwd, ReporterConfig.defaultFormat)

                      // TODO: Figure out a way to populate macros from previous run after restart
                      val ms = new Array[String](0)
                      val products =
                        CompileProducts(classesDir, classesDir, r, r, Set.empty, Map.empty, ms)
                      val bundle = ResultBundle(
                        Result.Success(inputs, reporter, products, 0L, dummyTasks, false, false),
                        Some(LastSuccessfulResult(inputs, products, Task.now(())))
                      )

                      // Compute a cleanup task if this is the first time loading this project
                      // It's fine to rerun this task whenever the classes directory changes
                      val cleanupTask = {
                        var cleanupTask0: Task[Unit] = Task.unit
                        val cleanupKey = AbsolutePath(classesDir)
                        cleanedOrphanDirsInBuild.computeIfAbsent(cleanupKey, (_: AbsolutePath) => {
                          cleanupTask0 = cleanUpOrphanedInternalDirs(p, cleanupKey)
                          true
                        })
                        cleanupTask0
                      }

                      bundle -> Some(cleanupTask)
                    case None =>
                      logger.debug(
                        s"Analysis '$analysisFile' last compilation for '${p.name}' didn't contain classes dir."
                      )
                      ResultBundle.empty -> None
                  }
                case None =>
                  logger.debug(
                    s"Analysis '$analysisFile' for '${p.name}' didn't contain last compilation."
                  )
                  ResultBundle.empty -> None
              }
            case None =>
              logger.debug(s"Analysis '$analysisFile' for '${p.name}' is empty.")
              ResultBundle.empty -> None
          }

        }
      } else {
        Task.now {
          logger.debug(s"Missing analysis file for project '${p.name}'")
          ResultBundle.empty -> None
        }
      }
    }

    val projects = build.loadedProjects.map(_.project)
    val all = projects.map(p => fetchPreviousResult(p).map(r => p -> r))
    Task.gatherUnordered(all).executeOn(ExecutionContext.ioScheduler).map { projectResults =>
      val newCache = new ResultsCache(Map.empty, Map.empty)
      val cleanupTasks = new mutable.ListBuffer[Task[Unit]]()
      val results = projectResults.foldLeft(newCache) {
        case (rs, (p, (result, cleanupTask))) =>
          cleanupTask.foreach(t => cleanupTasks.+=(t))
          rs.addResult(p, result)
      }

      // Spawn the cleanup tasks sequentially in the background and forget about it
      Task.sequence(cleanupTasks).materialize.runAsync(ExecutionContext.ioScheduler)

      // Return the collected results per project
      results
    }
  }
}
