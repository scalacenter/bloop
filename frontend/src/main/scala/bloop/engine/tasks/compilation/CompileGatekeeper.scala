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
  private val runningCompilations
      : Ref[MonixTask, Map[UniqueCompileInputs, Deferred[MonixTask, RunningCompilation]]] =
    Ref.unsafe(Map.empty)
  private val currentlyUsedClassesDirs: Ref[MonixTask, Map[AbsolutePath, AtomicInt]] =
    Ref.unsafe(Map.empty)
  private val lastSuccessfulResults: Ref[MonixTask, Map[ProjectId, LastSuccessfulResult]] =
    Ref.unsafe(Map.empty)

  /* -------------------------------------------------------------------------------------------- */

  def findRunningCompilationAtomically(
      inputs: BundleInputs,
      bundle: SuccessfulCompileBundle,
      client: ClientInfo,
      compile: SuccessfulCompileBundle => CompileTraversal
  ): Task[(RunningCompilation, CanBeDeduplicated)] = Task.liftMonixTaskUncancellable {
    Deferred[MonixTask, RunningCompilation].flatMap { deferred =>
      runningCompilations.modify { state =>
        state.get(bundle.uniqueInputs) match {
          case Some(existingDeferred) =>
            val output =
              existingDeferred.get
                .flatMap { running =>
                  val usedClassesDir = running.usedLastSuccessful.classesDir
                  val usedClassesDirCounter = running.usedLastSuccessful.counterForClassesDir
                  val deduplicate = usedClassesDirCounter.transformAndExtract {
                    case count if count == 0 => (false -> count)
                    case count => true -> (count + 1)
                  }
                  if (deduplicate) MonixTask.now((running, deduplicate))
                  else {
                    val classesDirs =
                      currentlyUsedClassesDirs
                        .update { classesDirs =>
                          if (
                            classesDirs
                              .get(usedClassesDir)
                              .contains(usedClassesDirCounter)
                          )
                            classesDirs - usedClassesDir
                          else
                            classesDirs
                        }
                    scheduleCompilation(inputs, bundle, client, compile)
                      .flatMap(compilation =>
                        deferred
                          .complete(compilation)
                          .flatMap(_ => classesDirs.map(_ => (compilation, false)))
                      )
                  }
                }
            state -> output
          case None =>
            val newState: Map[UniqueCompileInputs, Deferred[MonixTask, RunningCompilation]] =
              state + (bundle.uniqueInputs -> deferred)
            newState -> scheduleCompilation(inputs, bundle, client, compile).flatMap(compilation =>
              deferred.complete(compilation).map(_ => compilation -> false)
            )
        }
      }.flatten
    }
  }

  def disconnectDeduplicationFromRunning(
      inputs: UniqueCompileInputs,
      runningCompilation: RunningCompilation
  ): MonixTask[Unit] = {
    runningCompilation.isUnsubscribed.compareAndSet(false, true)
    runningCompilations.modify { state =>
      val updated =
        if (
          state.contains(inputs)
        ) // .contains(runningCompilationDeferred)) // TODO: no way to verfiy if it is the same
          state - inputs
        else state
      (updated, ())
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
  ): MonixTask[RunningCompilation] = {
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

    def getMostRecentSuccessfulResultAtomically: MonixTask[LastSuccessfulResult] = {
      lastSuccessfulResults.modify { state =>
        val previousResult =
          initializeLastSuccessful(state.get(project.uniqueId))
        state -> currentlyUsedClassesDirs
          .modify { counters =>
            counters.get(previousResult.classesDir) match {
              case None =>
                val initialCounter = AtomicInt(1)
                (counters + (previousResult.classesDir -> initialCounter), initialCounter)
              case Some(counter) =>
                val newCount = counter.incrementAndGet(1)
                logger.debug(s"Increasing counter for ${previousResult.classesDir} to $newCount")
                counters -> counter
            }
          }
          .map(_ => previousResult)
      }.flatten
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
            runningCompilations.update(_ - oinputs)
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
    runningCompilations
      .modify { state =>
        val newSuccessfulResults = (project.uniqueId, successful)
        if (state.contains(oracleInputs)) {
          val newState = state - oracleInputs
          (newState, lastSuccessfulResults.update { _ + newSuccessfulResults })
        } else {
          (state, MonixTask.unit)
        }
      }
      .flatten
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
