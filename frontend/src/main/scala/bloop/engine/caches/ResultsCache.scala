package bloop.engine.caches

import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

import bloop.CompileOutPaths
import bloop.CompileProducts
import bloop.Compiler
import bloop.Compiler.Result
import bloop.UniqueCompileInputs
import bloop.data.ClientInfo
import bloop.data.Project
import bloop.engine.Build
import bloop.engine.ExecutionContext
import bloop.engine.tasks.compilation.FinalCompileResult
import bloop.engine.tasks.compilation.FinalEmptyResult
import bloop.engine.tasks.compilation.FinalNormalCompileResult
import bloop.engine.tasks.compilation.ReloadFailure
import bloop.engine.tasks.compilation.ResultBundle
import bloop.io.AbsolutePath
import bloop.io.Paths
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.logging.ObservedLogger
import bloop.reporter.LogReporter
import bloop.reporter.ReporterConfig
import bloop.task.Task

import sbt.internal.inc.Analysis
import sbt.internal.inc.FileAnalysisStore
import xsbti.compile.PreviousResult

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

  /** Returns the last successful result if present, empty otherwise. */
  def lastSuccessfulResultOrEmpty(project: Project): LastSuccessfulResult =
    lastSuccessfulResult(project).getOrElse(LastSuccessfulResult.empty(project))

  /** Returns an optional last successful result. */
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
  def cleanSuccessful(
      projects: Set[Project],
      client: ClientInfo,
      logger: Logger
  ): Task[ResultsCache] = {
    def delete(path: AbsolutePath): Task[Unit] = Task {
      logger.debug(s"Deleting $path")(DebugFilter.All)
      Paths.delete(path)
    }
    // Remove all the successful results from the cache.
    val newSuccessful = successful.filterKeys(p => !projects.contains(p))
    val newAll = all.filterKeys(p => !projects.contains(p))
    val deleteClassesDirs = successful.filterKeys(projects.contains).flatMap {
      case (project, result) =>
        List(
          delete(result.classesDir),
          delete(client.getUniqueClassesDirFor(project, forceGeneration = false))
        )
    }
    // The persisted analysis is deleted for every target, not only those with an in-memory
    // result, so a clean fully resets incremental state and is reloaded fresh after a restart.
    val deleteAnalysisFiles = projects.iterator.map(p => delete(p.analysisOut)).toList

    Task.gatherUnordered(deleteClassesDirs.toList ++ deleteAnalysisFiles).map { _ =>
      new ResultsCache(newAll, newSuccessful)
    }
  }

  def allAnalysis: Seq[Analysis] = {
    successful.valuesIterator
      .flatMap(s => sbt.util.InterfaceUtil.toOption(s.previous.analysis()).toList)
      .collect { case a: Analysis => a }
      .toList
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

  /**
   * Replaces the in-memory results of `reloaded`. A project mapped to `None` had no persisted
   * analysis and is dropped, so its next compilation starts from a clean slate.
   */
  def replaceWithReloaded(reloaded: Map[Project, Option[ResultBundle]]): ResultsCache = {
    val projects = reloaded.keys
    val base = new ResultsCache(all -- projects, successful -- projects)
    reloaded.foldLeft(base) {
      case (rs, (_, None)) => rs
      case (rs, (p, Some(bundle))) => rs.addResult(p, bundle)
    }
  }

  /** Applies what this cache changed against `base` on top of `latest`, keeping concurrent work. */
  def mergeOnto(base: ResultsCache, latest: ResultsCache): ResultsCache = {
    def merge[T <: AnyRef](
        baseEntries: Map[Project, T],
        ourEntries: Map[Project, T],
        latestEntries: Map[Project, T]
    ): Map[Project, T] = {
      val changed = (baseEntries.keySet ++ ourEntries.keySet).filter { project =>
        baseEntries.get(project) match {
          case Some(before) => !ourEntries.get(project).exists(_ eq before)
          case None => ourEntries.contains(project)
        }
      }

      changed.foldLeft(latestEntries) {
        case (entries, project) =>
          ourEntries.get(project) match {
            case Some(ours) => entries + (project -> ours)
            case None => entries - project
          }
      }
    }

    new ResultsCache(
      merge(base.all, all, latest.all),
      merge(base.successful, successful, latest.successful)
    )
  }

  /** Sets the successful result of a single project. */
  private[bloop] def replaceSuccessful(
      project: Project,
      result: Option[LastSuccessfulResult]
  ): ResultsCache = {
    result match {
      case Some(value) => new ResultsCache(all, successful + (project -> value))
      case None => new ResultsCache(all, successful - project)
    }
  }

  /** Drops everything known about a project, as if it had never compiled. */
  private[bloop] def forget(project: Project): ResultsCache =
    new ResultsCache(all - project, successful - project)

  override def toString: String = {
    case class PrettyPrintedResultsCache(
        all: Map[String, Compiler.Result],
        successful: Map[String, LastSuccessfulResult]
    )
    val cache = PrettyPrintedResultsCache(
      all.map { case (key, value) => key.name -> value },
      successful.map { case (key, value) => key.name -> value }
    )
    pprint.apply(cache, height = Int.MaxValue).render
  }
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
    val projects = build.loadedProjects.map(_.project)
    val all =
      projects.map(p => fetchPreviousResult(p, cwd, cleanOrphanedInternalDirs, logger).map(p -> _))
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

  /**
   * Orphaned internal directories are directories that are not used by any
   * client and that are currently not the internal classes directory of any project.
   *
   * These are usually deleted by Bloop right away, but in some cases there might be left,
   * which means it's safe to delete them.
   *
   * An example path cleaned up by this method is:
   * .bloop/mtest/bloop-internal-classes/classes-Metals-12MpgYmRSGOOK5fxouxTqg==-iWRxh-luRTOxk3bOx9UAfg==
   */
  private def cleanUpOrphanedInternalDirs(
      project: Project,
      analysisClassesDir: AbsolutePath,
      logger: Logger
  ): Task[Unit] = {
    val internalClassesDir =
      CompileOutPaths.createInternalClassesRootDir(project.out)
    // This is a surprise, skip any cleanup if internal analysis dir doesn't
    // live under the internal classes dir root assigned to the project
    if (internalClassesDir != analysisClassesDir.getParent) Task.unit
    else {
      ClientInfo.toGenericClassesDir(analysisClassesDir) match {
        case Some(genericClassesName) =>
          val deleteOrphans = Task {
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
                logger.debug(
                  s"Discovered orphan directory $path"
                )(DebugFilter.All)
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

  /** Outcome of reading a persisted analysis: missing means no state, invalid means untrusted. */
  private[bloop] sealed trait PersistedResult
  private[bloop] object PersistedResult {
    final case class Loaded(bundle: ResultBundle, cleanup: Option[Task[Unit]])
        extends PersistedResult
    case object Missing extends PersistedResult
    final case class Invalid(reason: String) extends PersistedResult
  }

  private def fetchPreviousResult(
      p: Project,
      cwd: AbsolutePath,
      cleanOrphanedInternalDirs: Boolean,
      logger: Logger
  ): Task[(ResultBundle, Option[Task[Unit]])] = {
    Task(loadPersistedResult(p, cwd, cleanOrphanedInternalDirs, logger)).map {
      case PersistedResult.Loaded(bundle, cleanup) => bundle -> cleanup
      case PersistedResult.Missing => ResultBundle.empty -> None
      case PersistedResult.Invalid(reason) =>
        // A build must still load when a single analysis is unusable, so degrade to no state
        logger.warn(s"Ignoring persisted analysis for '${p.name}': $reason")
        ResultBundle.empty -> None
    }
  }

  /**
   * Reads the analysis persisted for `p`. Its recorded output directory is accepted only when
   * Bloop manages it, because compilation later deletes that directory once superseded.
   */
  private[bloop] def loadPersistedResult(
      p: Project,
      cwd: AbsolutePath,
      cleanOrphanedInternalDirs: Boolean,
      logger: Logger
  ): PersistedResult = {
    import bloop.util.JavaCompat.EnrichOptional
    val analysisFile = p.analysisOut
    if (!analysisFile.exists) {
      logger.debug(s"Missing analysis file for project '${p.name}'")
      PersistedResult.Missing
    } else {
      try {
        val contents = FileAnalysisStore.binary(analysisFile.toFile).get().toOption
        contents match {
          // Zinc reports content it cannot deserialize as no contents at all
          case None => PersistedResult.Invalid(s"analysis '$analysisFile' could not be read")
          case Some(res) =>
            logger.debug(s"Loading previous analysis for '${p.name}' from '$analysisFile'.")
            val r = PreviousResult.of(Optional.of(res.getAnalysis), Optional.of(res.getMiniSetup))
            val classesDirOrNone = for {
              lastCompilation <- res.getAnalysis.readCompilations.getAllCompilations.lastOption
              classesDir <- lastCompilation.getOutput.getSingleOutputAsPath.toOption
            } yield classesDir

            classesDirOrNone match {
              case None =>
                PersistedResult.Invalid(
                  s"analysis '$analysisFile' has no output directory for its last compilation"
                )
              case Some(classesDir) if !isInternalClassesDirOf(AbsolutePath(classesDir), p) =>
                PersistedResult.Invalid(
                  s"output directory '$classesDir' recorded in '$analysisFile' is not an internal " +
                    s"classes directory of '${p.name}'"
                )
              case Some(classesDir) if !AbsolutePath(classesDir).isDirectory =>
                PersistedResult.Invalid(
                  s"output directory '$classesDir' recorded in '$analysisFile' no longer exists"
                )
              case Some(classesDir) =>
                val originPath = p.origin.path.syntax
                val inputs = UniqueCompileInputs.emptyFor(originPath)
                val dummyTasks = bloop.CompileBackgroundTasks.empty
                val dummy = ObservedLogger.dummy(logger, ExecutionContext.ioScheduler)
                val reporter = new LogReporter(p, dummy, cwd, ReporterConfig.defaultFormat)

                val products = CompileProducts(classesDir, classesDir, r, r, Set.empty, Map.empty)
                val bundle = ResultBundle(
                  Result.Success(reporter, products, 0L, dummyTasks, false, false, None),
                  Some(LastSuccessfulResult(inputs, products, Task.now(()))),
                  None
                )

                // Compute a cleanup task if this is the first time loading this project
                // It's fine to rerun this task whenever the classes directory changes
                val cleanupTask = {
                  if (!cleanOrphanedInternalDirs) None
                  else {
                    var cleanupTask0: Task[Unit] = Task.unit
                    val cleanupKey = AbsolutePath(classesDir)
                    cleanedOrphanDirsInBuild.computeIfAbsent(
                      cleanupKey,
                      (_: AbsolutePath) => {
                        cleanupTask0 = cleanUpOrphanedInternalDirs(p, cleanupKey, logger)
                        true
                      }
                    )
                    Some(cleanupTask0)
                  }
                }

                PersistedResult.Loaded(bundle, cleanupTask)
            }
        }
      } catch {
        case NonFatal(t) =>
          logger.trace(t)
          PersistedResult.Invalid(s"analysis '$analysisFile' could not be read: ${t.getMessage}")
      }
    }
  }

  /**
   * Tells whether `classesDir` is an internal classes directory Bloop made for `project`.
   * Compilation deletes it once superseded, so no foreign path may take its place.
   */
  private def isInternalClassesDirOf(classesDir: AbsolutePath, project: Project): Boolean = {
    val internalClassesRoot = CompileOutPaths.createInternalClassesRootDir(project.out)
    val dir = classesDir.underlying.normalize()
    dir.getParent == internalClassesRoot.underlying.normalize() &&
    ClientInfo.toGenericClassesDir(classesDir).isDefined
  }

  /**
   * Reads the analysis persisted for every project, failing if any exists but cannot be used:
   * callers replace state with the result, so a bad file must not destroy what Bloop holds.
   */
  private[bloop] def loadPersistedResults(
      projects: List[Project],
      cwd: AbsolutePath,
      logger: Logger
  ): Either[ReloadFailure, Map[Project, Option[ResultBundle]]] = {
    val loaded = projects.map { p =>
      // Pruning here would delete directories the state being replaced still points at
      p -> loadPersistedResult(p, cwd, cleanOrphanedInternalDirs = false, logger)
    }

    val invalid = loaded.collect {
      case (p, PersistedResult.Invalid(reason)) => s"'${p.name}': $reason"
    }

    if (invalid.nonEmpty) {
      Left(
        ReloadFailure.UnusableState(s"Cannot reload compilation state, ${invalid.mkString("; ")}")
      )
    } else {
      Right(loaded.map {
        case (p, PersistedResult.Loaded(bundle, _)) => p -> Some(bundle)
        case (p, _) => p -> None
      }.toMap)
    }
  }
}
