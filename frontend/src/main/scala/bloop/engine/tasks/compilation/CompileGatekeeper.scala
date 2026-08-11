package bloop.engine.tasks.compilation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import bloop.Compiler
import bloop.UniqueCompileInputs
import bloop.data.ClientInfo
import bloop.data.Project
import bloop.engine.Dag
import bloop.engine.State
import bloop.engine.caches.LastSuccessfulResult
import bloop.engine.caches.ResultsCache
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.logging.LoggerAction
import bloop.reporter.ReporterAction
import bloop.task.Task
import bloop.tracing.BraveTracer

import monix.execution.atomic.AtomicBoolean
import monix.reactive.Observable

object CompileGatekeeper {
  private implicit val filter: DebugFilter = DebugFilter.Compilation
  import bloop.engine.tasks.compilation.CompileDefinitions._

  private[bloop] final case class RunningCompilation(
      traversal: CompileTraversal,
      usedLastSuccessful: LastSuccessfulResult,
      isUnsubscribed: AtomicBoolean,
      mirror: Observable[Either[ReporterAction, LoggerAction]],
      client: ClientInfo
  )

  /* -------------------------------------------------------------------------------------------- */

  private val runningCompilations = new ConcurrentHashMap[UniqueCompileInputs, RunningCompilation]()

  /**
   * What this module knows about a project. A tombstone is not the same as knowing nothing: it
   * stops a client that still carries the removed result from bringing it back.
   */
  private[bloop] sealed trait CompilationEntry
  private[bloop] object CompilationEntry {

    /** The successful state compilation builds on. Its absence is a [[Tombstone]]. */
    final case class Present(successful: LastSuccessfulResult) extends CompilationEntry
    case object Tombstone extends CompilationEntry
  }

  private val lastSuccessfulResults = new ConcurrentHashMap[ProjectId, CompilationEntry]()

  /**
   * Counts the events that changed a project's state, so a reload can tell whether anything
   * happened while it read from disk. Entries cannot: a clean leaves tombstone over tombstone.
   */
  private val generations = new ConcurrentHashMap[ProjectId, java.lang.Long]()

  private def bumpGeneration(id: ProjectId): Unit = {
    generations.merge(id, java.lang.Long.valueOf(1L), (a, b) => a + b)
    ()
  }

  private def generationOf(id: ProjectId): Long =
    Option(generations.get(id)).map(_.longValue).getOrElse(0L)

  /**
   * Serializes admitting a compilation, registering its result and reloading, which read and
   * write both maps as a unit. Always taken before any map lock, never the other way around.
   */
  private val stateLock = new Object

  /* -------------------------------------------------------------------------------------------- */

  def findRunningCompilationAtomically(
      inputs: BundleInputs,
      bundle: SuccessfulCompileBundle,
      client: ClientInfo,
      compile: SuccessfulCompileBundle => CompileTraversal,
      tracer: BraveTracer
  ): (RunningCompilation, CanBeDeduplicated) =
    tracer.trace("Finding compilation atomically.") { tracer =>
      import bundle.logger
      var deduplicate = true

      // Admitting a compilation reads the last successful result, so it cannot interleave with
      // a reload replacing it
      val running = stateLock.synchronized {
        runningCompilations.compute(
          bundle.uniqueInputs,
          (_: UniqueCompileInputs, running: RunningCompilation) => {
            // A disconnected compilation keeps running, so it still reserves its project even
            // though no new client may deduplicate onto it
            if (running == null || running.isUnsubscribed.get) {
              tracer.trace(
                "no running compilation found starting new one",
                ("uniqueInputs", bundle.uniqueInputs.toString)
              ) { tracer =>
                logger.debug(
                  s"no running compilation found starting new one:${bundle.uniqueInputs}"
                )
                deduplicate = false
                scheduleCompilation(inputs, bundle, client, compile, tracer)
              }
            } else {
              tracer.trace(
                "Found matching compilation",
                ("uniqueInputs", bundle.uniqueInputs.toString)
              ) { tracer =>
                if (deduplicate) running
                else scheduleCompilation(inputs, bundle, client, compile, tracer)
              }
            }
          }
        )
      }

      (running, deduplicate)
    }

  def disconnectDeduplicationFromRunning(
      inputs: UniqueCompileInputs,
      runningCompilation: RunningCompilation,
      logger: Logger,
      tracer: BraveTracer
  ): Unit = {
    tracer.trace(
      "disconnectDeduplicationFromRunning",
      ("uniqueInputs", inputs.toString)
    ) { _ =>
      logger.debug(s"Disconnected deduplication from running compilation:${inputs}")
      // Cancellation is cooperative, so the compilation may run for a while yet. It stays in
      // the map, where it reserves its project against a reload, until it terminally completes
      runningCompilation.isUnsubscribed.compareAndSet(false, true)
      ()
    }
  }

  /**
   * Schedules a unique compilation for the given inputs.
   *
   * This compilation can be deduplicated by other clients that have the same
   * inputs. The call-site ensures that only one compilation can exist for the
   * same inputs for a period of time.
   */
  private def scheduleCompilation(
      inputs: BundleInputs,
      bundle: SuccessfulCompileBundle,
      client: ClientInfo,
      compile: SuccessfulCompileBundle => CompileTraversal,
      tracer: BraveTracer
  ): RunningCompilation =
    tracer.trace("schedule compilation") { _ =>
      import bundle.logger
      import inputs.project

      def initializeLastSuccessful(previousOrNull: CompilationEntry): LastSuccessfulResult =
        tracer.trace(s"initialize last successful") { _ =>
          // Whether the coordinator knows the state, or this client is the only one who does
          val isAuthoritative = previousOrNull != null
          val result = previousOrNull match {
            case CompilationEntry.Present(previous) => previous
            case CompilationEntry.Tombstone =>
              // The state was removed on purpose, so the one this client carries is obsolete
              logger.debug(s"Ignoring analysis for ${project.name}, its state was removed")
              LastSuccessfulResult.empty(inputs.project)
            case null => bundle.lastSuccessful
          }
          if (!result.classesDir.exists) {
            logger.debug(
              s"Ignoring analysis for ${project.name}, directory ${result.classesDir} is missing"
            )
            LastSuccessfulResult.empty(inputs.project)
          } else if (!isAuthoritative && bundle.latestResult == Compiler.Result.Empty) {
            // Only this client's own leftover state is dropped for having no last result. State
            // the coordinator holds is newer than anything the client knows, which is exactly
            // the case after state was imported into a server that had not compiled yet
            logger.debug(s"Ignoring existing analysis for ${project.name}, last result was empty")
            LastSuccessfulResult
              .empty(inputs.project)
              // Replace classes dir, counter and populating with values from previous for correctness
              .copy(
                classesDir = result.classesDir,
                populatingProducts = result.populatingProducts
              )
          } else {
            logger.debug(
              s"Using successful result for ${project.name} associated with ${result.classesDir}"
            )
            result
          }
        }

      def getMostRecentSuccessfulResultAtomically =
        tracer.trace("get most recent successful result atomically") { _ =>
          lastSuccessfulResults
            .compute(
              project.uniqueId,
              (_: String, previousResultOrNull: CompilationEntry) => {
                logger.debug(
                  s"Return previous result or the initial last successful coming from the bundle:${project.uniqueId}"
                )
                // Return previous result or the initial last successful coming from the bundle
                CompilationEntry.Present(initializeLastSuccessful(previousResultOrNull))
              }
            ) match {
            case CompilationEntry.Present(result) => result
            case _ => LastSuccessfulResult.empty(project)
          }
        }

      logger.debug(s"Scheduling compilation for ${project.name}...")
      bumpGeneration(project.uniqueId)

      // Replace client-specific last successful with the most recent result
      val mostRecentSuccessful = getMostRecentSuccessfulResultAtomically

      val isUnsubscribed = AtomicBoolean(false)
      val runningRef = new AtomicReference[RunningCompilation]()
      val newBundle = bundle.copy(lastSuccessful = mostRecentSuccessful)
      val compileAndUnsubscribe = tracer.trace("compile and unsubscribe") { _ =>
        compile(newBundle)
          .doOnFinish(_ => Task(logger.observer.onComplete()))
          .map { result =>
            // Unregister deduplication atomically and register last successful if any
            tracer.trace("process result atomically") { _ =>
              processResultAtomically(
                result,
                project,
                bundle.uniqueInputs,
                runningRef,
                logger,
                tracer
              )
            } // Without memoization, there is no deduplication
          }
          .memoize
      }

      val runningCompilation = RunningCompilation(
        compileAndUnsubscribe,
        mostRecentSuccessful,
        isUnsubscribed,
        bundle.mirror,
        client
      )

      runningRef.set(runningCompilation)
      runningCompilation
    }

  private def processResultAtomically(
      resultDag: Dag[PartialCompileResult],
      project: Project,
      oinputs: UniqueCompileInputs,
      running: AtomicReference[RunningCompilation],
      logger: Logger,
      tracer: BraveTracer
  ): Dag[PartialCompileResult] = {

    def cleanUpAfterCompilationError[T](result: T): T =
      tracer.trace("cleaning after compilation error") { _ =>
        logger.debug(s"Remove running compilation that finished without a result:${oinputs}")
        // Only this compilation may be retired: a disconnected one can have been replaced by a
        // newer compilation registered under the same inputs
        runningCompilations.remove(oinputs, running.get)
        result
      }

    // Unregister deduplication atomically and register last successful if any
    PartialCompileResult.mapEveryResult(resultDag) {
      case s: PartialSuccess =>
        val processedResult = s.result.map { (result: ResultBundle) =>
          result.successful match {
            case None =>
              tracer.trace("cleaning after compilation error") { _ =>
                cleanUpAfterCompilationError(result)
              }
            case Some(res) =>
              tracer.trace("unregister deduplication and register successful") { _ =>
                unregisterDeduplicationAndRegisterSuccessful(project, oinputs, running, res, logger)
              }
          }
          result
        }

        /**
         * This result task must only be run once and thus needs to be
         * memoized for correctness reasons. The result task can be called
         * several times by the compilation engine driving the execution.
         */
        s.copy(result = processedResult.memoize)

      case result =>
        tracer.trace("cleaning after compilation error") { _ =>
          cleanUpAfterCompilationError(result)
        }
    }
  }

  /**
   * Removes the deduplication and registers the last successful compilation
   * atomically. When registering the last successful compilation, we make sure
   * that the old last successful result is deleted if its count is 0, which
   * means it's not being used by anyone.
   */
  private def unregisterDeduplicationAndRegisterSuccessful(
      project: Project,
      oracleInputs: UniqueCompileInputs,
      running: AtomicReference[RunningCompilation],
      successful: LastSuccessfulResult,
      logger: Logger
  ): Unit = {
    // Retiring the compilation and publishing its result is one step for a concurrent reload,
    // which must never observe the project as idle before its result is registered
    stateLock.synchronized {
      runningCompilations.compute(
        oracleInputs,
        (_: UniqueCompileInputs, current: RunningCompilation) => {
          logger.debug("Unregister deduplication and registered successfully")
          lastSuccessfulResults
            .compute(project.uniqueId, (_, _) => CompilationEntry.Present(successful))
          bumpGeneration(project.uniqueId)
          // Retire this compilation only, leaving any newer one that replaced it in place
          if (current == null || (current eq running.get)) null else current
        }
      )
    }

    logger.debug(
      s"Recording new last successful request for ${project.name} associated with ${successful.classesDir}"
    )

    ()
  }

  /**
   * Returns the most recent successful result for a project, if any. It is
   * registered when a target finishes compiling, before its `taskFinish` is
   * emitted, so read endpoints can consult it instead of the per-connection
   * state (which only observes new results at the end of the compile request).
   */
  def latestSuccessfulResult(project: Project): Option[LastSuccessfulResult] =
    Option(lastSuccessfulResults.get(project.uniqueId)).collect {
      case CompilationEntry.Present(result) => result
    }

  /** Tells whether any of `projects` has a compilation in flight. */
  private def isCompilingAnyOf(projects: List[Project]): Boolean = {
    import scala.collection.JavaConverters._
    val origins = projects.iterator.map(_.origin.path.syntax).toSet
    runningCompilations.keys().asScala.exists(inputs => origins.contains(inputs.originProjectPath))
  }

  private[bloop] final val BusyProjectsError =
    "Cannot reload compilation state while projects compile, retry when the build is idle"
  private[bloop] final val ConcurrentChangeError =
    "Compilation state changed while it was being reloaded, retry when the build is idle"
  private[bloop] final val CleaningProjectsError =
    "Cannot reload compilation state while projects are cleaned, retry when the build is idle"

  private val cleaningProjects = new java.util.HashSet[ProjectId]()

  /**
   * Runs `clean` with `projects` reserved against reloads. The reservation lasts until it
   * finishes because its deletions do, and a reload in between would publish vanishing state.
   */
  private[bloop] def cleaning[T](projects: List[Project])(clean: Task[T]): Task[T] = {
    val ids = projects.map(_.uniqueId)
    val reserve = Task {
      stateLock.synchronized {
        ids.foreach { id =>
          lastSuccessfulResults.put(id, CompilationEntry.Tombstone)
          bumpGeneration(id)
        }
        ids.foreach(id => cleaningProjects.add(id))
      }
    }

    val release = Task {
      stateLock.synchronized(ids.foreach(id => cleaningProjects.remove(id)))
      ()
    }

    reserve.flatMap(_ => clean).doOnFinish(_ => release)
  }

  private def isCleaningAnyOf(projects: List[Project]): Boolean =
    projects.exists(p => cleaningProjects.contains(p.uniqueId))

  /**
   * Replaces the last successful result of `projects` with what `loadFromDisk` reads, provided
   * nothing happened to them meanwhile. `publish` runs in the same critical section.
   */
  private[bloop] def reloadSuccessfulResults(
      projects: List[Project]
  )(
      loadFromDisk: () => Either[ReloadFailure, Map[Project, Option[ResultBundle]]]
  )(
      publish: Map[Project, Option[ResultBundle]] => Unit
  ): Either[ReloadFailure, Map[Project, Option[ResultBundle]]] = {
    def generationsOf(ps: List[Project]): Map[ProjectId, Long] =
      ps.map(p => p.uniqueId -> generationOf(p.uniqueId)).toMap

    // Deciding that the build is idle and recording where it stood must be one step: anything
    // starting afterwards has to take this lock to bump a generation, so the final check sees it
    val baseline = stateLock.synchronized {
      if (isCleaningAnyOf(projects)) Left(ReloadFailure.Cleaning(CleaningProjectsError))
      else if (isCompilingAnyOf(projects)) Left(ReloadFailure.Busy(BusyProjectsError))
      else Right(generationsOf(projects))
    }

    baseline.flatMap { generationsBeforeRead =>
      loadFromDisk().flatMap { reloaded =>
        stateLock.synchronized {
          val changed = projects.exists { p =>
            generationOf(p.uniqueId) != generationsBeforeRead(p.uniqueId)
          }

          if (isCleaningAnyOf(projects)) Left(ReloadFailure.Cleaning(CleaningProjectsError))
          else if (isCompilingAnyOf(projects)) Left(ReloadFailure.Busy(BusyProjectsError))
          else if (changed) Left(ReloadFailure.ConcurrentChange(ConcurrentChangeError))
          else {
            reloaded.foreach {
              case (p, bundle) =>
                bumpGeneration(p.uniqueId)
                val entry = bundle.flatMap(_.successful) match {
                  case Some(successful) => CompilationEntry.Present(successful)
                  // Record that there is no state rather than forgetting the project, so a
                  // client still holding the replaced result cannot reinstate it
                  case None => CompilationEntry.Tombstone
                }
                lastSuccessfulResults.put(p.uniqueId, entry)
            }
            // Both stores are updated here, while compilations are held out, so no compilation
            // can land between them and be overwritten by the second write
            publish(reloaded)
            Right(reloaded)
          }
        }
      }
    }
  }

  /**
   * Publishes `next` on behalf of a request that started from `previous`, under this module's
   * lock. Taking it before the cache's is also the order a reload uses, so neither can wait.
   */
  private[bloop] def commitState(previous: State, next: State): State = {
    stateLock.synchronized {
      State.stateCache.commit(previous, next)(reconcile(next))
    }
  }

  /**
   * Replaces every project's successful result with the one this module holds, so a request
   * publishing later than it ran cannot reinstate what it knew. Requires the state lock.
   */
  private def reconcile(state: State)(results: ResultsCache): ResultsCache = {
    state.build.loadedProjects.foldLeft(results) {
      case (reconciled, loaded) =>
        val project = loaded.project
        Option(lastSuccessfulResults.get(project.uniqueId)) match {
          case Some(CompilationEntry.Present(successful)) =>
            reconciled.replaceSuccessful(project, Some(successful))
          // State that was removed on purpose leaves no result of any kind behind, as a clean
          // and a reload that found nothing on disk both do
          case Some(CompilationEntry.Tombstone) => reconciled.forget(project)
          case None => reconciled
        }
    }
  }

  // Expose clearing mechanism so that it can be invoked in the tests and community build runner
  private[bloop] def clearSuccessfulResults(): Unit = {
    stateLock.synchronized {
      lastSuccessfulResults.clear()
      generations.clear()
    }
  }

  // Drop the cached last successful result for a single project so a clean fully resets it.
  private[bloop] def clearSuccessfulResult(project: Project): Unit = {
    stateLock.synchronized {
      lastSuccessfulResults.put(project.uniqueId, CompilationEntry.Tombstone)
      bumpGeneration(project.uniqueId)
    }
    ()
  }
}
