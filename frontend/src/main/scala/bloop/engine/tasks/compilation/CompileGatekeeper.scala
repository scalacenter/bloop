package bloop.engine.tasks.compilation

import bloop.Compiler
import bloop.UniqueCompileInputs
import bloop.data.ClientInfo
import bloop.data.Project
import bloop.engine.Dag
import bloop.engine.caches.LastSuccessfulResult
import bloop.io.AbsolutePath
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.logging.LoggerAction
import bloop.reporter.ReporterAction
import bloop.task.Task
import monix.eval.{Task => MonixTask}
import cats.effect.concurrent.{Deferred, Ref}
import monix.execution.atomic.AtomicBoolean
import monix.execution.atomic.AtomicInt
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

  case class CompilerState(
      currentlyUsedClassesDirs: Map[AbsolutePath, AtomicInt],
      runningCompilations: Map[UniqueCompileInputs, RunningCompilation],
      lastSuccessfulResults: Map[ProjectId, LastSuccessfulResult]
  )
  object CompilerState {
    def empty: CompilerState = CompilerState(Map.empty, Map.empty, Map.empty)
  }

  private val compilerStateRef: Ref[MonixTask, CompilerState] = Ref.unsafe(CompilerState.empty)

  /* -------------------------------------------------------------------------------------------- */

  def findRunningCompilationAtomically(
      inputs: BundleInputs,
      bundle: SuccessfulCompileBundle,
      client: ClientInfo,
      compile: SuccessfulCompileBundle => CompileTraversal
  ): Task[(RunningCompilation, CanBeDeduplicated)] =
    scheduleCompilation(inputs, bundle, client, compile)
      .flatMap { orCompilation =>
        Task.liftMonixTaskUncancellable {
          compilerStateRef
            .modify { state =>
              val currentCompilation = state.runningCompilations.get(bundle.uniqueInputs)
              val (compilation, deduplicate, classesDirs) = currentCompilation
                .fold(
                  (
                    orCompilation,
                    false,
                    state.currentlyUsedClassesDirs
                  )
                ) { running =>
                  val usedClassesDir = running.usedLastSuccessful.classesDir
                  val usedClassesDirCounter = running.usedLastSuccessful.counterForClassesDir
                  val deduplicate = usedClassesDirCounter.transformAndExtract {
                    case count if count == 0 => (false -> count)
                    case count => true -> (count + 1)
                  }
                  if (deduplicate) (running, deduplicate, state.currentlyUsedClassesDirs)
                  else {
                    val classesDirs =
                      if (
                        state.currentlyUsedClassesDirs
                          .get(usedClassesDir)
                          .contains(usedClassesDirCounter)
                      )
                        state.currentlyUsedClassesDirs - usedClassesDir
                      else
                        state.currentlyUsedClassesDirs
                    (
                      orCompilation,
                      deduplicate,
                      classesDirs
                    )
                  }
                }
              val newState =
                state.copy(
                  currentlyUsedClassesDirs = classesDirs,
                  runningCompilations =
                    state.runningCompilations + (bundle.uniqueInputs -> compilation)
                )
              (state, (compilation, deduplicate))
            }
        }
      }

  def disconnectDeduplicationFromRunning(
      inputs: UniqueCompileInputs,
      runningCompilation: RunningCompilation
  ): Task[Unit] = Task.liftMonixTaskUncancellable {
    runningCompilation.isUnsubscribed.compareAndSet(false, true)
    compilerStateRef.modify { state =>
      val updated =
        if (state.runningCompilations.get(inputs).contains(runningCompilation))
          state.runningCompilations - inputs
        else state.runningCompilations
      (state.copy(runningCompilations = updated), ())
    }
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
  ): Task[RunningCompilation] = {
    import inputs.project
    import bundle.logger
    import logger.debug

    def initializeLastSuccessful(
        maybePreviousResult: Option[LastSuccessfulResult]
    ): LastSuccessfulResult = {
      val result = maybePreviousResult.getOrElse(bundle.lastSuccessful)
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

    def getMostRecentSuccessfulResultAtomically = Task.liftMonixTaskUncancellable {
      compilerStateRef.modify { state =>
        val previousResult =
          initializeLastSuccessful(state.lastSuccessfulResults.get(project.uniqueId))
        val counter = state.currentlyUsedClassesDirs
          .get(previousResult.classesDir)
          .fold {
            val initialCounter = AtomicInt(1)
            initialCounter
          } { counter =>
            val newCount = counter.incrementAndGet(1)
            logger.debug(s"Increasing counter for ${previousResult.classesDir} to $newCount")
            counter
          }
        val newUserClassesDir = (previousResult.classesDir, counter)
        val newResults = (project.uniqueId, previousResult)
        state.copy(
          lastSuccessfulResults = state.lastSuccessfulResults + newResults,
          currentlyUsedClassesDirs = state.currentlyUsedClassesDirs + newUserClassesDir
        ) -> previousResult
      }
    }

    logger.debug(s"Scheduling compilation for ${project.name}...")

    getMostRecentSuccessfulResultAtomically
      .map { mostRecentSuccessful =>
        val isUnsubscribed = AtomicBoolean(false)
        val newBundle = bundle.copy(lastSuccessful = mostRecentSuccessful)
        val compileAndUnsubscribe = compile(newBundle)
          .doOnFinish(_ => Task(logger.observer.onComplete()))
          .flatMap { result =>
            // Unregister deduplication atomically and register last successful if any
            processResultAtomically(
              result,
              project,
              bundle.uniqueInputs,
              isUnsubscribed,
              logger
            )
          }
          .memoize

        RunningCompilation(
          compileAndUnsubscribe,
          mostRecentSuccessful,
          isUnsubscribed,
          bundle.mirror,
          client
        ) // Without memoization, there is no deduplication
      }

  }

  private def processResultAtomically(
      resultDag: Dag[PartialCompileResult],
      project: Project,
      oinputs: UniqueCompileInputs,
      isAlreadyUnsubscribed: AtomicBoolean,
      logger: Logger
  ): Task[Dag[PartialCompileResult]] = {

    def cleanUpAfterCompilationError[T](result: T): Task[T] = {
      Task {
        if (!isAlreadyUnsubscribed.get) {
          // Remove running compilation if host compilation hasn't unsubscribed (maybe it's blocked)
          Task.liftMonixTaskUncancellable {
            compilerStateRef.update { state =>
              state.copy(runningCompilations = state.runningCompilations - oinputs)
            }
          }
        } else
          Task.unit
      }.flatten.map(_ => result)
    }

    // Unregister deduplication atomically and register last successful if any
    PartialCompileResult.mapEveryResultTask(resultDag) {
      case s: PartialSuccess =>
        val processedResult = s.result.flatMap { (result: ResultBundle) =>
          result.successful
            .fold(cleanUpAfterCompilationError(result)) { res =>
              unregisterDeduplicationAndRegisterSuccessful(
                project,
                oinputs,
                res,
                logger
              )
                .map(_ => result)
            }
        }

        /**
         * This result task must only be run once and thus needs to be
         * memoized for correctness reasons. The result task can be called
         * several times by the compilation engine driving the execution.
         */
        Task(s.copy(result = processedResult.memoize))

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
  ): Task[Unit] = Task.liftMonixTaskUncancellable {
    compilerStateRef
      .update { state =>
        val newSuccessfulResults = (project.uniqueId, successful)
        if (state.runningCompilations.contains(oracleInputs)) {
          state
            .copy(
              lastSuccessfulResults = state.lastSuccessfulResults + newSuccessfulResults,
              runningCompilations = (state.runningCompilations - oracleInputs)
            )
        } else {
          state
        }
      }
      .map { _ =>
        logger.debug(
          s"Recording new last successful request for ${project.name} associated with ${successful.classesDir}"
        )

        ()
      }
  }

  // Expose clearing mechanism so that it can be invoked in the tests and community build runner
//  private[bloop] def clearSuccessfulResults(): Unit = {
//    lastSuccessfulResults.synchronized {
//      lastSuccessfulResults.clear()
//    }
//  }
}
