// scalafmt: { maxColumn = 130 }
package bloop.engine.tasks.compilation

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

import scala.util.Failure
import scala.util.Success

import ch.epfl.scala.bsp.StatusCode
import ch.epfl.scala.bsp.{StatusCode => BspStatusCode}

import bloop.CompileBackgroundTasks
import bloop.CompileExceptions.BlockURI
import bloop.CompileExceptions.FailedOrCancelledPromise
import bloop.Compiler
import bloop.data.ClientInfo
import bloop.data.Project
import bloop.engine.Aggregate
import bloop.engine.Dag
import bloop.engine.ExecutionContext
import bloop.engine.Leaf
import bloop.engine.Parent
import bloop.engine.tasks.compilation.CompileDefinitions.CompileTraversal
import bloop.logging.DebugFilter
import bloop.logging.LoggerAction
import bloop.reporter.ReporterAction
import bloop.task.Task
import bloop.util.JavaCompat.EnrichOptional
import bloop.util.SystemProperties
import bloop.util.BestEffortUtils.BestEffortProducts

import xsbti.compile.PreviousResult

object CompileGraph {
  import bloop.engine.tasks.compilation.CompileDefinitions._

  case class Inputs(
      bundle: SuccessfulCompileBundle,
      dependentResults: Map[File, PreviousResult]
  )

  private def partialSuccess(
      bundle: SuccessfulCompileBundle,
      result: ResultBundle
  ): PartialSuccess = PartialSuccess(bundle, Task.now(result))

  private def blockedBy(dag: Dag[PartialCompileResult]): Option[Project] = {
    dag match {
      case Leaf(_: PartialSuccess) => None
      case Leaf(f: PartialFailure) => Some(f.project)
      case Leaf(PartialEmpty) => None
      case Parent(_: PartialSuccess, _) => None
      case Parent(f: PartialFailure, _) => Some(f.project)
      case Parent(PartialEmpty, _) => None
      case Aggregate(dags) =>
        dags.foldLeft(None: Option[Project]) {
          case (acc, dag) =>
            acc match {
              case Some(_) => acc
              case None => blockedBy(dag)
            }

        }
    }
  }

  private sealed trait DeduplicationResult
  private object DeduplicationResult {
    final case object Ok extends DeduplicationResult
    final case object DisconnectFromDeduplication extends DeduplicationResult
    final case class DeduplicationError(t: Throwable) extends DeduplicationResult
  }

  /**
   * Sets up project compilation, deduplicates compilation based on ongoing compilations in all
   * concurrent clients and otherwise runs the compilation of a project.
   *
   * The correctness of the compile deduplication depends on the effects that different clients
   * perceive. For example, it would be incorrect to deduplicate the logic by memoizing the
   * compilation task and not forwarding all the side effects produced during the compilation to
   * all clients. This method takes care of replaying all the events that happen during the
   * compilation of a given project, regardless of the time where clients ask for the same
   * compilation. Most of the magic about how this is setup can be found in [[CompileTask]],
   * where the `setup` function is defined. The compile bundle contains both the observer to append
   * events, that is added to the reporter and logger, as well as the stream to consume the events.
   *
   * @param project The project we want to set up and compile.
   * @param setup The setup function that yields a bundle with unique oracle inputs.
   * @param compile The function that will compile the project.
   * @return A task that may be created by `compile` or may be a reference to a previous task.
   */
  def setupAndDeduplicate(
      client: ClientInfo,
      inputs: BundleInputs,
      setup: BundleInputs => Task[CompileBundle]
  )(
      compile: SuccessfulCompileBundle => CompileTraversal
  ): CompileTraversal = {
    def partialFailure(
        errorMsg: String,
        err: Option[Throwable]
    ): Task[Dag[PartialCompileResult]] = {
      val errInfo = err.map(e => s": ${errorToString(e)}").getOrElse("")
      val finalErrorMsg = s"$errorMsg$errInfo"
      val failedResult = Compiler.Result.GlobalError(finalErrorMsg, err)
      val failed = Task.now(ResultBundle(failedResult, None, None))
      Task.now(Leaf(PartialFailure(inputs.project, FailedOrCancelledPromise, failed)))
    }

    implicit val filter = DebugFilter.Compilation
    def withBundle(f: SuccessfulCompileBundle => CompileTraversal): CompileTraversal = {
      setup(inputs).materialize.flatMap {
        case Success(bundle: SuccessfulCompileBundle) =>
          f(bundle).materialize.flatMap {
            case Success(result) => Task.now(result)
            case Failure(err) =>
              val msg = s"Unexpected exception when compiling ${inputs.project}"
              bundle.logger.trace(err)
              partialFailure(msg, Some(err))
          }

        case Success(CancelledCompileBundle) =>
          val result = Compiler.Result.Cancelled(Nil, 0L, CompileBackgroundTasks.empty)
          val failed = Task.now(ResultBundle(result, None, None))
          Task.now(Leaf(PartialFailure(inputs.project, FailedOrCancelledPromise, failed)))
        case Failure(err) =>
          val msg = s"Unexpected exception when computing compile inputs for ${inputs.project}"
          partialFailure(msg, Some(err))
      }
    }

    withBundle { bundle0 =>
      val logger = bundle0.logger
      val (runningCompilation, deduplicate) =
        CompileGatekeeper.findRunningCompilationAtomically(inputs, bundle0, client, compile)
      val bundle = bundle0.copy(lastSuccessful = runningCompilation.usedLastSuccessful)

      if (!deduplicate) {
        runningCompilation.traversal
      } else {
        val rawLogger = logger.underlying
        rawLogger.info(
          s"Deduplicating compilation of ${bundle.project.name} from ${runningCompilation.client}"
        )
        val reporter = bundle.reporter.underlying
        // Don't use `bundle.lastSuccessful`, it's not the final input to `compile`
        val analysis = runningCompilation.usedLastSuccessful.previous.analysis().toOption
        val previousSuccessfulProblems =
          Compiler.previousProblemsFromSuccessfulCompilation(analysis)
        val wasPreviousSuccessful = bundle.latestResult match {
          case Compiler.Result.Ok(_) => true
          case _ => false
        }
        val previousProblems =
          Compiler.previousProblemsFromResult(bundle.latestResult, previousSuccessfulProblems)

        val clientClassesObserver = client.getClassesObserverFor(bundle.project)

        // Replay events asynchronously to waiting for the compilation result
        import scala.concurrent.duration.FiniteDuration
        import monix.execution.exceptions.UpstreamTimeoutException
        val disconnectionTime = SystemProperties.getCompileDisconnectionTime(rawLogger)
        val replayEventsTask = runningCompilation.mirror
          .timeoutOnSlowUpstream(disconnectionTime)
          .foreachL {
            case Left(action) =>
              action match {
                case ReporterAction.EnableFatalWarnings =>
                  reporter.enableFatalWarnings()
                case ReporterAction.ReportStartCompilation =>
                  reporter.reportStartCompilation(previousProblems, wasPreviousSuccessful)
                case a: ReporterAction.ReportStartIncrementalCycle =>
                  reporter.reportStartIncrementalCycle(a.sources, a.outputDirs)
                case a: ReporterAction.ReportProblem => reporter.log(a.problem)
                case ReporterAction.PublishDiagnosticsSummary =>
                  reporter.printSummary()
                case a: ReporterAction.ReportNextPhase =>
                  reporter.reportNextPhase(a.phase, a.sourceFile)
                case a: ReporterAction.ReportCompilationProgress =>
                  reporter.reportCompilationProgress(a.progress, a.total)
                case a: ReporterAction.ReportEndIncrementalCycle =>
                  reporter.reportEndIncrementalCycle(a.durationMs, a.result)
                case ReporterAction.ReportCancelledCompilation =>
                  reporter.reportCancelledCompilation()
                case a: ReporterAction.ProcessEndCompilation =>
                  a.code match {
                    case BspStatusCode.Cancelled | BspStatusCode.Error =>
                      reporter.processEndCompilation(previousProblems, a.code, None, None)
                      reporter.reportEndCompilation()
                    case _ =>
                      /*
                       * Only process the end, don't report it. It's only safe to
                       * report when all the client tasks have been run and the
                       * analysis/classes dirs are fully populated so that clients
                       * can use `taskFinish` notifications as a signal to process them.
                       */
                      reporter.processEndCompilation(
                        previousProblems,
                        a.code,
                        Some(clientClassesObserver.classesDir),
                        Some(bundle.out.analysisOut)
                      )
                  }
              }
            case Right(action) =>
              action match {
                case LoggerAction.LogErrorMessage(msg) => rawLogger.error(msg)
                case LoggerAction.LogWarnMessage(msg) => rawLogger.warn(msg)
                case LoggerAction.LogInfoMessage(msg) => rawLogger.info(msg)
                case LoggerAction.LogDebugMessage(msg) =>
                  rawLogger.debug(msg)
                case LoggerAction.LogTraceMessage(msg) =>
                  rawLogger.debug(msg)
              }
          }
          .materialize
          .map {
            case Success(_) => DeduplicationResult.Ok
            case Failure(_: UpstreamTimeoutException) =>
              DeduplicationResult.DisconnectFromDeduplication
            case Failure(t) => DeduplicationResult.DeduplicationError(t)
          }

        /* The task set up by another process whose memoized result we're going to
         * reuse. To prevent blocking compilations, we execute this task (which will
         * block until its completion is done) in the IO thread pool, which is
         * unbounded. This makes sure that the blocking threads *never* block
         * the computation pool, which could produce a hang in the build server.
         */
        val runningCompilationTask =
          runningCompilation.traversal.executeOn(ExecutionContext.ioScheduler)

        val deduplicateStreamSideEffectsHandle =
          replayEventsTask.runToFuture(ExecutionContext.ioScheduler)

        /**
         * Deduplicate and change the implementation of the task returning the
         * deduplicate compiler result to trigger a syncing process to keep the
         * client external classes directory up-to-date with the new classes
         * directory. This copying process blocks until the background IO work
         * of the deduplicated compilation result has been finished. Note that
         * this mechanism allows pipelined compilations to perform this IO only
         * when the full compilation of a module is finished.
         */
        val obtainResultFromDeduplication = runningCompilationTask.map { results =>
          PartialCompileResult.mapEveryResult(results) {
            case s @ PartialSuccess(bundle, compilerResult) =>
              val newCompilerResult = compilerResult.flatMap { results =>
                results.fromCompiler match {
                  case s: Compiler.Result.Success =>
                    // Wait on new classes to be populated for correctness
                    val runningBackgroundTasks = s.backgroundTasks
                      .trigger(clientClassesObserver, reporter, bundle.tracer, logger)
                      .runAsync(ExecutionContext.ioScheduler)
                    Task.now(results.copy(runningBackgroundTasks = runningBackgroundTasks))
                  case _: Compiler.Result.Cancelled =>
                    // Make sure to cancel the deduplicating task if compilation is cancelled
                    deduplicateStreamSideEffectsHandle.cancel()
                    Task.now(results)
                  case _ => Task.now(results)
                }
              }
              s.copy(result = newCompilerResult)
            case result => result
          }
        }

        val compileAndDeduplicate = Task
          .chooseFirstOf(
            obtainResultFromDeduplication,
            Task.fromFuture(deduplicateStreamSideEffectsHandle)
          )
          .executeOn(ExecutionContext.ioScheduler)

        val finalCompileTask = compileAndDeduplicate.flatMap {
          case Left((result, deduplicationFuture)) =>
            Task.fromFuture(deduplicationFuture).map(_ => result)
          case Right((compilationFuture, deduplicationResult)) =>
            deduplicationResult match {
              case DeduplicationResult.Ok => Task.fromFuture(compilationFuture)
              case DeduplicationResult.DeduplicationError(t) =>
                rawLogger.trace(t)
                val failedDeduplicationResult = Compiler.Result.GlobalError(
                  s"Unexpected error while deduplicating compilation for ${inputs.project.name}: ${t.getMessage}",
                  Some(t)
                )

                /*
                 * When an error happens while replaying all events of the
                 * deduplicated compilation, we keep track of the error, wait
                 * until the deduplicated compilation finishes and then we
                 * replace the result by a failed result that informs the
                 * client compilation was not successfully deduplicated.
                 */
                Task.fromFuture(compilationFuture).map { results =>
                  PartialCompileResult.mapEveryResult(results) { (p: PartialCompileResult) =>
                    p match {
                      case s: PartialSuccess =>
                        val failedBundle = ResultBundle(failedDeduplicationResult, None, None)
                        s.copy(result = s.result.map(_ => failedBundle))
                      case result => result
                    }
                  }
                }

              case DeduplicationResult.DisconnectFromDeduplication =>
                /*
                 * Deduplication timed out after no compilation updates were
                 * recorded. In theory, this could happen because a rogue
                 * compilation process has stalled or is blocked. To ensure
                 * deduplicated clients always make progress, we now proceed
                 * with:
                 *
                 * 1. Cancelling the dead-looking compilation, hoping that the
                 *    process will wake up at some point and stop running.
                 * 2. Shutting down the deduplication and triggering a new
                 *    compilation. If there are several clients deduplicating this
                 *    compilation, they will compete to start the compilation again
                 *    with new compile inputs, as they could have already changed.
                 * 3. Reporting the end of compilation in case it hasn't been
                 *    reported. Clients must handle two end compilation notifications
                 *    gracefully.
                 * 4. Display the user that the deduplication was cancelled and a
                 *    new compilation was scheduled.
                 */

                CompileGatekeeper.disconnectDeduplicationFromRunning(
                  bundle.uniqueInputs,
                  runningCompilation
                )

                compilationFuture.cancel()
                reporter.processEndCompilation(Nil, StatusCode.Cancelled, None, None)
                reporter.reportEndCompilation()

                logger.displayWarningToUser(
                  s"""Disconnecting from deduplication of ongoing compilation for '${inputs.project.name}'
                     |No progress update for ${(disconnectionTime: FiniteDuration)
                      .toString()} caused bloop to cancel compilation and schedule a new compile.
                  """.stripMargin
                )

                setupAndDeduplicate(client, inputs, setup)(compile)
            }
        }

        bundle.tracer.traceTask(s"deduplicating ${bundle.project.name}") { _ =>
          finalCompileTask.executeOn(ExecutionContext.ioScheduler)
        }
      }
    }
  }

  import scala.collection.mutable

  /**
   * Turns a dag of projects into a task that returns a dag of compilation results
   * that can then be used to debug the evaluation of the compilation within Monix
   * and access the compilation results received from Zinc.
   *
   * @param dag The dag of projects to be compiled.
   * @return A task that returns a dag of compilation results.
   */
  def traverse(
      dag: Dag[Project],
      client: ClientInfo,
      store: CompileClientStore,
      bestEffortAllowed: Boolean,
      computeBundle: BundleInputs => Task[CompileBundle],
      compile: (Inputs, Boolean, Boolean) => Task[ResultBundle]
  ): CompileTraversal = {
    val tasks = new mutable.HashMap[Dag[Project], CompileTraversal]()
    def register(k: Dag[Project], v: CompileTraversal): CompileTraversal = {
      val toCache = store.findPreviousTraversalOrAddNew(k, v).getOrElse(v)
      tasks.put(k, toCache)
      toCache
    }

    /*
     * [[PartialCompileResult]] is our way to represent errors at the build graph
     * so that we can block the compilation of downstream projects. As we have to
     * abide by this contract because it's used by the pipeline traversal too, we
     * turn an actual compiler failure into a partial failure with a dummy
     * `FailPromise` exception that makes the partial result be recognized as error.
     */
    def toPartialFailure(bundle: SuccessfulCompileBundle, results: ResultBundle): PartialFailure = {
      PartialFailure(bundle.project, FailedOrCancelledPromise, Task.now(results))
    }

    def loop(dag: Dag[Project], isBestEffortDep: Boolean): CompileTraversal = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task: Task[Dag[PartialCompileResult]] = dag match {
            case Leaf(project) =>
              val bundleInputs = BundleInputs(project, dag, Map.empty)
              setupAndDeduplicate(client, bundleInputs, computeBundle) { bundle =>
                compile(Inputs(bundle, Map.empty), bestEffortAllowed && project.isBestEffort, isBestEffortDep).map { results =>
                  results.fromCompiler match {
                    case Compiler.Result.Ok(_) => Leaf(partialSuccess(bundle, results))
                    case _ => Leaf(toPartialFailure(bundle, results))
                  }
                }
              }

            case Aggregate(dags) =>
              val downstream = dags.map(loop(_, isBestEffortDep = false))
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                Task.now(Parent(PartialEmpty, dagResults))
              }

            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop(_, isBestEffortDep = false))
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                val depsSupportBestEffort =
                  dependencies.map(Dag.dfs(_, mode = Dag.PreOrder)).flatten.forall(_.isBestEffort)
                val failed = dagResults.flatMap(dag => blockedBy(dag).toList)

                val allResults = Task.gatherUnordered {
                  val transitive = dagResults.flatMap(Dag.dfs(_, mode = Dag.PreOrder)).distinct
                  transitive.flatMap {
                    case PartialSuccess(bundle, result) => Some(result.map(r => bundle.project -> r))
                    case PartialFailure(project, _, result) => Some(result.map(r => project -> r))
                    case _ => None
                  }
                }

                allResults.flatMap { results =>
                  val successfulBestEffort = !results.exists {
                    case (_, ResultBundle(f: Compiler.Result.Failed, _, _, _)) => f.bestEffortProducts.isEmpty
                    case _ => false
                  }
                  val continue = bestEffortAllowed && depsSupportBestEffort && successfulBestEffort || failed.isEmpty
                  val dependsOnBestEffort = failed.nonEmpty && bestEffortAllowed && depsSupportBestEffort || isBestEffortDep

                  if (!continue) {
                    // Register the name of the projects we're blocked on (intransitively)
                    val blockedResult = Compiler.Result.Blocked(failed.map(_.name))
                    val blocked = Task.now(ResultBundle(blockedResult, None, None))
                    Task.now(Parent(PartialFailure(project, BlockURI, blocked), dagResults))
                  } else {
                    val dependentProducts = new mutable.ListBuffer[(Project, BundleProducts)]()
                    val dependentResults = new mutable.ListBuffer[(File, PreviousResult)]()
                    results.foreach {
                      case (p, ResultBundle(s: Compiler.Result.Success, _, _, _)) =>
                        val newProducts = s.products
                        dependentProducts.+=(p -> Right(newProducts))
                        val newResult = newProducts.resultForDependentCompilationsInSameRun
                        dependentResults
                          .+=(newProducts.newClassesDir.toFile -> newResult)
                          .+=(newProducts.readOnlyClassesDir.toFile -> newResult)
                      case (p, ResultBundle(f: Compiler.Result.Failed, _, _, _)) =>
                        f.bestEffortProducts.foreach {
                          case BestEffortProducts(products, _) =>
                            dependentProducts += (p -> Right(products))
                        }
                      case _ => ()
                    }

                    val resultsMap = dependentResults.toMap
                    val bundleInputs = BundleInputs(project, dag, dependentProducts.toMap)
                    setupAndDeduplicate(client, bundleInputs, computeBundle) { bundle =>
                      val inputs = Inputs(bundle, resultsMap)
                      compile(inputs, bestEffortAllowed && project.isBestEffort, dependsOnBestEffort).map { results =>
                        results.fromCompiler match {
                          case Compiler.Result.Ok(_) if failed.isEmpty =>
                            Parent(partialSuccess(bundle, results), dagResults)
                          case _ => Parent(toPartialFailure(bundle, results), dagResults)
                        }
                      }
                    }
                  }
                }
              }
          }
          register(dag, task.memoize)
      }
    }

    loop(dag, isBestEffortDep = false)
  }

  private def errorToString(err: Throwable): String = {
    val sw = new StringWriter()
    val pw = new PrintWriter(sw)
    err.printStackTrace(pw)
    sw.toString
  }
}
