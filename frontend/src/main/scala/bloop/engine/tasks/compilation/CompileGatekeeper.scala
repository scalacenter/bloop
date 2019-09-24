package bloop.engine.tasks.compilation

import bloop.Compiler
import bloop.engine.{Dag, Leaf, Parent, Aggregate}
import bloop.data.Project
import bloop.UniqueCompileInputs
import bloop.engine.caches.LastSuccessfulResult
import bloop.data.ClientInfo
import bloop.logging.Logger
import bloop.CompileOutPaths
import bloop.logging.DebugFilter
import bloop.engine.ExecutionContext
import bloop.reporter.ReporterAction
import bloop.logging.LoggerAction
import bloop.io.AbsolutePath

import monix.execution.atomic.AtomicBoolean
import monix.eval.Task
import monix.reactive.Observable
import monix.execution.atomic.AtomicInt

import java.util.concurrent.ConcurrentHashMap

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

  private val currentlyUsedClassesDirs = new ConcurrentHashMap[AbsolutePath, AtomicInt]()
  private val runningCompilations = new ConcurrentHashMap[UniqueCompileInputs, RunningCompilation]()
  private val lastSuccessfulResults = new ConcurrentHashMap[ProjectId, LastSuccessfulResult]()

  /* -------------------------------------------------------------------------------------------- */

  def findRunningCompilationAtomically(
      inputs: BundleInputs,
      bundle: SuccessfulCompileBundle,
      client: ClientInfo,
      compile: SuccessfulCompileBundle => CompileTraversal
  ): (RunningCompilation, CanBeDeduplicated) = {
    var deduplicate = true

    val running = runningCompilations.compute(
      bundle.uniqueInputs,
      (_: UniqueCompileInputs, running: RunningCompilation) => {
        if (running == null) {
          deduplicate = false
          scheduleCompilation(inputs, bundle, client, compile)
        } else {
          val usedClassesDir = running.usedLastSuccessful.classesDir
          val usedClassesDirCounter = running.usedLastSuccessful.counterForClassesDir

          usedClassesDirCounter.getAndTransform {
            count =>
              if (count == 0) {
                // Abort deduplication, dir is scheduled to be deleted in background
                deduplicate = false
                // Remove from map of used classes dirs in case it hasn't already been
                currentlyUsedClassesDirs.remove(usedClassesDir, usedClassesDirCounter)
                // Return previous count, this counter will soon be deallocated
                count
              } else {
                // Increase count to prevent other compiles to schedule its deletion
                count + 1
              }
          }

          if (deduplicate) running
          else scheduleCompilation(inputs, bundle, client, compile)
        }
      }
    )

    (running, deduplicate)
  }

  def disconnectDeduplicationFromRunning(
      inputs: UniqueCompileInputs,
      runningCompilation: RunningCompilation
  ): Unit = {
    runningCompilation.isUnsubscribed.compareAndSet(false, true)
    runningCompilations.remove(inputs, runningCompilation); ()
  }

  /**
   * Schedules a unique compilation for the given inputs.
   *
   * This compilation can be deduplicated by other clients that have the same
   * inputs. The call-site ensures that only one compilation can exist for the
   * same inputs for a period of time.
   */
  def scheduleCompilation(
      inputs: BundleInputs,
      bundle: SuccessfulCompileBundle,
      client: ClientInfo,
      compile: SuccessfulCompileBundle => CompileTraversal
  ): RunningCompilation = {
    import inputs.project
    import bundle.logger
    import logger.debug
    import bundle.cancelCompilation

    var counterForUsedClassesDir: AtomicInt = null

    def initializeLastSuccessful(previousOrNull: LastSuccessfulResult): LastSuccessfulResult = {
      val result = Option(previousOrNull).getOrElse(bundle.lastSuccessful)
      debug(s"Checking result of ${result.classesDir}")
      if (!result.classesDir.exists) {
        debug(s"Ignoring analysis for ${project.name}, directory ${result.classesDir} is missing")
        LastSuccessfulResult.empty(inputs.project)
      } else if (bundle.latestResult == Compiler.Result.Empty) {
        debug(s"Ignoring existing analysis for ${project.name}, last result was empty")
        LastSuccessfulResult
          .empty(inputs.project)
          // Replace classes dir, counter and populating with values from previous for correctness
          .copy(
            classesDir = result.classesDir,
            counterForClassesDir = result.counterForClassesDir,
            populatingProducts = result.populatingProducts
          )
      } else {
        debug(s"Using successful result for ${project.name} associated with ${result.classesDir}")
        result
      }
    }

    def getMostRecentSuccessfulResultAtomically = {
      lastSuccessfulResults.compute(
        project.uniqueId,
        (_: String, previousResultOrNull: LastSuccessfulResult) => {
          // Return previous result or the initial last successful coming from the bundle
          val previousResult = initializeLastSuccessful(previousResultOrNull)

          currentlyUsedClassesDirs.compute(
            previousResult.classesDir,
            (_: AbsolutePath, counter: AtomicInt) => {
              // Set counter for used classes dir when init or incrementing
              if (counter == null) {
                val initialCounter = AtomicInt(1)
                counterForUsedClassesDir = initialCounter
                initialCounter
              } else {
                counterForUsedClassesDir = counter
                val newCount = counter.incrementAndGet(1)
                logger.debug(s"Increasing counter for ${previousResult.classesDir} to $newCount")
                counter
              }
            }
          )

          previousResult.copy(counterForClassesDir = counterForUsedClassesDir)
        }
      )
    }

    logger.debug(s"Scheduling compilation for ${project.name}...")

    // Replace client-specific last successful with the most recent result
    val mostRecentSuccessful = getMostRecentSuccessfulResultAtomically

    val isUnsubscribed = AtomicBoolean(false)
    val newBundle = bundle.copy(lastSuccessful = mostRecentSuccessful)
    val compileAndUnsubscribe = {
      compile(newBundle)
        .doOnFinish(_ => Task(logger.observer.onComplete()))
        .map { result =>
          // Unregister deduplication atomically and register last successful if any
          processResultAtomically(
            result,
            project,
            bundle.uniqueInputs,
            mostRecentSuccessful,
            isUnsubscribed,
            client,
            logger
          )
        }
        .memoize // Without memoization, there is no deduplication
    }

    RunningCompilation(
      compileAndUnsubscribe,
      mostRecentSuccessful,
      isUnsubscribed,
      bundle.mirror,
      client
    )
  }

  private def processResultAtomically(
      resultDag: Dag[PartialCompileResult],
      project: Project,
      oinputs: UniqueCompileInputs,
      previous: LastSuccessfulResult,
      isAlreadyUnsubscribed: AtomicBoolean,
      client: ClientInfo,
      logger: Logger
  ): Dag[PartialCompileResult] = {

    def cleanUpAfterCompilationError[T](result: T): T = {
      if (!isAlreadyUnsubscribed.get) {
        // Remove running compilation if host compilation hasn't unsubscribed (maybe it's blocked)
        runningCompilations.remove(oinputs)
      }

      result
    }

    // Unregister deduplication atomically and register last successful if any
    PartialCompileResult.mapEveryResult(resultDag) {
      case s: PartialSuccess =>
        val processedResult = s.result.map { (result: ResultBundle) =>
          result.successful match {
            case None => cleanUpAfterCompilationError(result)
            case Some(res) =>
              unregisterDeduplicationAndRegisterSuccessful(project, oinputs, res, logger)
          }
          result
        }

        /**
         * This result task must only be run once and thus needs to be
         * memoized for correctness reasons. The result task can be called
         * several times by the compilation engine driving the execution.
         */
        s.copy(result = processedResult.memoize)

      case result => cleanUpAfterCompilationError(result)
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
      successful: LastSuccessfulResult,
      logger: Logger
  ): Unit = {
    logger.debug(
      s"Successful classes dir ${successful.classesDir} exists: ${successful.classesDir.exists}"
    )
    runningCompilations.compute(
      oracleInputs,
      (_: UniqueCompileInputs, _: RunningCompilation) => {
        lastSuccessfulResults.compute(project.uniqueId, (_, _) => successful)
        logger.debug(
          s"Recording new last successful request for ${project.name} associated with ${successful.classesDir}"
        )
        null
      }
    )
    ()
  }

  // Expose clearing mechanism so that it can be invoked in the tests and community build runner
  private[bloop] def clearSuccessfulResults(): Unit = {
    lastSuccessfulResults.synchronized {
      lastSuccessfulResults.clear()
    }
  }
}
