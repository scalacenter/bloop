package bloop.engine.tasks.compilation

import bloop.{Compiler, UniqueCompileInputs}
import bloop.data.{ClientInfo, Project}
import bloop.engine.Dag
import bloop.engine.caches.LastSuccessfulResult
import bloop.io.AbsolutePath
import bloop.logging.{DebugFilter, Logger, LoggerAction}
import bloop.reporter.ReporterAction
import bloop.task.Task
import bloop.tracing.BraveTracer
import monix.execution.atomic.{AtomicBoolean, AtomicInt}
import monix.reactive.Observable

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
      compile: SuccessfulCompileBundle => CompileTraversal,
      tracer: BraveTracer
  ): (RunningCompilation, CanBeDeduplicated) =
    tracer.trace("Finding compilation atomically.") { tracer =>
      import bundle.logger
      var deduplicate = true

      val running = runningCompilations.compute(
        bundle.uniqueInputs,
        (_: UniqueCompileInputs, running: RunningCompilation) => {
          if (running == null) {
            tracer.trace(
              "no running compilation found starting new one",
              ("uniqueInputs", bundle.uniqueInputs.toString)
            ) { tracer =>
              logger.debug(s"no running compilation found starting new one:${bundle.uniqueInputs}")
              deduplicate = false
              scheduleCompilation(inputs, bundle, client, compile, tracer)
            }
          } else {
            tracer.trace(
              "Found matching compilation",
              ("uniqueInputs", bundle.uniqueInputs.toString)
            ) { tracer =>
              val usedClassesDir = running.usedLastSuccessful.classesDir
              val usedClassesDirCounter = running.usedLastSuccessful.counterForClassesDir

              usedClassesDirCounter.getAndTransform { count =>
                if (count == 0) {
                  tracer.trace(
                    "Aborting deduplication",
                    ("uniqueInputs", bundle.uniqueInputs.toString)
                  ) { tracer =>
                    logger.debug(
                      s"Abort deduplication, dir is scheduled to be deleted in background:${bundle.uniqueInputs}"
                    )
                    // Abort deduplication, dir is scheduled to be deleted in background
                    deduplicate = false
                    // Remove from map of used classes dirs in case it hasn't already been
                    currentlyUsedClassesDirs.remove(usedClassesDir, usedClassesDirCounter)
                    // Return previous count, this counter will soon be deallocated
                    count
                  }
                } else {
                  tracer.trace(
                    "Increasing compilation counter",
                    ("uniqueInputs", bundle.uniqueInputs.toString)
                  ) { tracer =>
                    logger.debug(
                      s"Increase count to prevent other compiles to schedule its deletion:${bundle.uniqueInputs}"
                    )
                    // Increase count to prevent other compiles to schedule its deletion
                    count + 1
                  }
                }
              }

              if (deduplicate) running
              else scheduleCompilation(inputs, bundle, client, compile, tracer)
            }
          }
        }
      )

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
      runningCompilation.isUnsubscribed.compareAndSet(false, true)
      runningCompilations.remove(inputs, runningCompilation); ()
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

      var counterForUsedClassesDir: AtomicInt = null

      def initializeLastSuccessful(previousOrNull: LastSuccessfulResult): LastSuccessfulResult =
        tracer.trace(s"initialize last successful") { _ =>
          val result = Option(previousOrNull).getOrElse(bundle.lastSuccessful)
          if (!result.classesDir.exists) {
            logger.debug(
              s"Ignoring analysis for ${project.name}, directory ${result.classesDir} is missing"
            )
            LastSuccessfulResult.empty(inputs.project)
          } else if (bundle.latestResult == Compiler.Result.Empty) {
            logger.debug(s"Ignoring existing analysis for ${project.name}, last result was empty")
            LastSuccessfulResult
              .empty(inputs.project)
              // Replace classes dir, counter and populating with values from previous for correctness
              .copy(
                classesDir = result.classesDir,
                counterForClassesDir = result.counterForClassesDir,
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
          lastSuccessfulResults.compute(
            project.uniqueId,
            (_: String, previousResultOrNull: LastSuccessfulResult) => {
              logger.debug(
                s"Return previous result or the initial last successful coming from the bundle:${project.uniqueId}"
              )
              // Return previous result or the initial last successful coming from the bundle
              val previousResult = initializeLastSuccessful(previousResultOrNull)

              currentlyUsedClassesDirs.compute(
                previousResult.classesDir,
                (_: AbsolutePath, counter: AtomicInt) => {
                  logger.debug(
                    s"Set counter for used classes dir when init or incrementing:${previousResult.classesDir}"
                  )
                  // Set counter for used classes dir when init or incrementing
                  if (counter == null) {
                    logger.debug(s"Create new counter:${previousResult.classesDir}")
                    val initialCounter = AtomicInt(1)
                    counterForUsedClassesDir = initialCounter
                    initialCounter
                  } else {
                    counterForUsedClassesDir = counter
                    val newCount = counter.incrementAndGet(1)
                    logger.debug(
                      s"Increasing counter for ${previousResult.classesDir} to $newCount"
                    )
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
                isUnsubscribed,
                logger,
                tracer
              )
            } // Without memoization, there is no deduplication
          }
          .memoize
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
      isAlreadyUnsubscribed: AtomicBoolean,
      logger: Logger,
      tracer: BraveTracer
  ): Dag[PartialCompileResult] = {

    def cleanUpAfterCompilationError[T](result: T): T =
      tracer.trace("cleaning after compilation error") { _ =>
        if (!isAlreadyUnsubscribed.get) {
          // Remove running compilation if host compilation hasn't unsubscribed (maybe it's blocked)
          logger.debug(
            s"Remove running compilation if host compilation hasn't unsubscribed (maybe it's blocked):${oinputs}"
          )
          runningCompilations.remove(oinputs)
        }

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
                unregisterDeduplicationAndRegisterSuccessful(project, oinputs, res, logger)
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
      successful: LastSuccessfulResult,
      logger: Logger
  ): Unit = {
    runningCompilations.compute(
      oracleInputs,
      (_: UniqueCompileInputs, _: RunningCompilation) => {
        logger.debug("Unregister deduplication and registered successfully")
        lastSuccessfulResults.compute(project.uniqueId, (_, _) => successful)
        null
      }
    )

    logger.debug(
      s"Recording new last successful request for ${project.name} associated with ${successful.classesDir}"
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
