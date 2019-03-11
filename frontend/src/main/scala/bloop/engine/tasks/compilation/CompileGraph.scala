// scalafmt: { maxcolumn = 130 }
package bloop.engine.tasks.compilation

import java.io.File
import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap}

import bloop.data.{Project, ClientInfo}
import bloop.engine.tasks.compilation.CompileExceptions.BlockURI
import bloop.util.Java8Compat.JavaCompletableFutureUtils
import bloop.engine._
import bloop.reporter.ReporterAction
import bloop.logging.{Logger, ObservedLogger, LoggerAction, DebugFilter}
import bloop.{Compiler, CompilerOracle, JavaSignal, SimpleIRStore, CompileProducts}
import bloop.io.ParallelOps
import bloop.io.ParallelOps.CopyMode

import monix.eval.Task
import monix.reactive.{Observable, MulticastStrategy}
import xsbti.compile.{EmptyIRStore, IR, IRStore, PreviousResult}

import scala.util.{Failure, Success}

object CompileGraph {
  type CompileTraversal = Task[Dag[PartialCompileResult]]

  case class BundleInputs(
      project: Project,
      dag: Dag[Project],
      dependentProducts: Map[Project, CompileProducts]
  )

  type IRs = Array[IR]
  case class Inputs(
      bundle: CompileBundle,
      store: IRStore,
      irPromise: CompletableFuture[IRs],
      completeJava: CompletableFuture[Unit],
      transitiveJavaSignal: Task[JavaSignal],
      oracle: CompilerOracle[PartialSuccess],
      separateJavaAndScala: Boolean,
      dependentResults: Map[File, PreviousResult] = Map.empty
  )

  object Inputs {
    def normal(
        b: CompileBundle,
        s: IRStore,
        p: CompletableFuture[IRs],
        oracle: CompilerOracle[PartialSuccess],
        separateJavaAndScala: Boolean,
        dependentResults: Map[File, PreviousResult] = Map.empty
    ): Inputs = {
      Inputs(b, s, p, JavaCompleted, JavaContinue, oracle, separateJavaAndScala, dependentResults)
    }
  }

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
      setup: BundleInputs => Task[CompileBundle],
      compile: Inputs => Task[Compiler.Result],
      pipeline: Boolean
  ): CompileTraversal = {
    /* We use different traversals for normal and pipeline compilation because the
     * pipeline traversal has an small overhead (2-3%) for some projects. Check
     * https://benchs.scala-lang.org/dashboard/snapshot/sLrZTBfntTxMWiXJPtIa4DIrmT0QebYF */
    if (pipeline) pipelineTraversal(dag, client, setup, compile)
    else normalTraversal(dag, client, setup, compile)
  }

  private final val JavaContinue = Task.now(JavaSignal.ContinueCompilation)
  private final val JavaCompleted = {
    val cf = new CompletableFuture[Unit](); cf.complete(()); cf
  }

  private def blockedBy(dag: Dag[PartialCompileResult]): Option[Project] = {
    def blockedFromResults(results: List[PartialCompileResult]): Option[Project] = {
      results match {
        case Nil => None
        case result :: rest =>
          result match {
            case PartialEmpty => None
            case _: PartialSuccess => None
            case f: PartialFailure => Some(f.project)
            case fs: PartialFailures => blockedFromResults(results)
          }
      }
    }

    dag match {
      case Leaf(_: PartialSuccess) => None
      case Leaf(f: PartialFailure) => Some(f.project)
      case Leaf(fs: PartialFailures) => blockedFromResults(fs.failures)
      case Leaf(PartialEmpty) => None
      case Parent(_: PartialSuccess, _) => None
      case Parent(f: PartialFailure, _) => Some(f.project)
      case Parent(fs: PartialFailures, _) => blockedFromResults(fs.failures)
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

  case class RunningCompilation(
      traversal: CompileTraversal,
      mirror: Observable[Either[ReporterAction, LoggerAction]]
  )

  type RunningCompilationsInAllClients =
    ConcurrentHashMap[CompilerOracle.Inputs, RunningCompilation]
  private val runningCompilations: RunningCompilationsInAllClients =
    new ConcurrentHashMap[CompilerOracle.Inputs, RunningCompilation]()

  private val emptyOracle = ImmutableCompilerOracle.empty()

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
      compile: CompileBundle => CompileTraversal
  ): CompileTraversal = {
    implicit val filter = DebugFilter.Compilation
    setup(inputs).flatMap { bundle =>
      val logger = bundle.logger
      var deduplicate: Boolean = true
      val ongoingCompilation = runningCompilations.computeIfAbsent(
        bundle.oracleInputs,
        (_: CompilerOracle.Inputs) => {
          deduplicate = false
          val compileAndUnsubscribe = compile(bundle).map { result =>
            // Remove as ongoing compilation before returning
            runningCompilations.remove(bundle.oracleInputs)
            // Let's not forget to complete the observer logger
            logger.observer.onComplete()
            // Return compilation result after previous book-keeping
            result
          }.memoize // Without memoization, there is no deduplication

          RunningCompilation(compileAndUnsubscribe, bundle.mirror)
        }
      )

      if (!deduplicate) {
        ongoingCompilation.traversal
      } else {
        val rawLogger = logger.underlying
        rawLogger.debug(s"Deduplicating compilation for ${bundle.project.name}")
        val reporter = bundle.reporter.underlying
        // Replay events asynchronously to waiting for the compilation result
        val replayEventsTask = ongoingCompilation.mirror.foreachL {
          case Left(action) =>
            action match {
              case a: ReporterAction.ReportStartCompilation =>
                reporter.reportStartCompilation(a.previousProblems)
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
              case a: ReporterAction.ReportEndCompilation =>
                reporter.reportEndCompilation(a.previousSuccessfulProblems, a.code)
            }
          case Right(action) =>
            action match {
              case LoggerAction.LogErrorMessage(msg) => rawLogger.error(msg)
              case LoggerAction.LogWarnMessage(msg) => rawLogger.warn(msg)
              case LoggerAction.LogInfoMessage(msg) => rawLogger.info(msg)
              case LoggerAction.LogDebugMessage(msg) =>
                rawLogger.debug(msg)(DebugFilter.Compilation)
              case LoggerAction.LogTraceMessage(msg) =>
                rawLogger.debug(msg)(DebugFilter.Compilation)
            }
        }

        /* The task set up by another process whose memoized result we're going to
         * reuse. To prevent blocking compilations, we execute this task (which will
         * block until its completion is done) in the IO thread pool, which is
         * unbounded. This makes sure that the blocking threads *never* block
         * the computation pool, which could produce a hang in the build server.
         */
        val ongoingCompilationTask =
          ongoingCompilation.traversal.executeOn(ExecutionContext.ioScheduler)

        /**
         * Deduplicate and change the implementation of the task returning the
         * deduplicate compiler result to trigger a syncing process to keep the
         * client external classes directory up-to-date with the new classes
         * directory. This copying process blocks until the background IO work
         * of the deduplicated compilation result has been finished. Note that
         * this mechanism allows pipelined compilations to perform this IO only
         * when the full compilation of a module is finished.
         */
        Task.mapBoth(ongoingCompilationTask, replayEventsTask) {
          case (resultDag, _) =>
            def finishDeduplication(result: PartialCompileResult): PartialCompileResult = {
              result match {
                case s @ PartialSuccess(bundle, _, _, _, compilerResult) =>
                  val newCompilerResult = compilerResult.flatMap {
                    case s: Compiler.Result.Success =>
                      // Wait on new classes to be populated for correctness
                      val blockOnBackgroundInIOThread =
                        Task.fromFuture(s.backgroundTasks).executeOn(ExecutionContext.ioScheduler)
                      blockOnBackgroundInIOThread.flatMap { _ =>
                        val externalClassesDir = client.getUniqueClassesDirFor(bundle.project)
                        val populatedClassesDir = s.products.newClassesDir
                        val config =
                          ParallelOps.CopyConfiguration(5, CopyMode.ReplaceExisting, Set.empty)
                        val copyTask = ParallelOps
                          .copyDirectories(config)(
                            populatedClassesDir,
                            externalClassesDir.underlying,
                            ExecutionContext.ioScheduler,
                            rawLogger
                          )
                        copyTask.map(_ => s)
                      }
                    case r => Task.now(r)
                  }
                  s.copy(result = newCompilerResult)
                case _ => result
              }
            }

            resultDag match {
              case Leaf(result) => Leaf(finishDeduplication(result))
              case Parent(result, children) => Parent(finishDeduplication(result), children)
              case Aggregate(_) =>
                logger.error("Unexpected aggregate node in compile result, report upstream!")
                resultDag
            }
        }
      }
    }
  }

  import scala.collection.mutable

  /**
   * Traverses the dag of projects in a normal way.
   *
   * @param dag is the dag of projects.
   * @param computeBundle is the function that sets up the project on every node.
   * @param compile is the task we use to compile on every node.
   * @return A task that returns a dag of compilation results.
   */
  private def normalTraversal(
      dag: Dag[Project],
      client: ClientInfo,
      computeBundle: BundleInputs => Task[CompileBundle],
      compile: Inputs => Task[Compiler.Result]
  ): CompileTraversal = {
    val tasks = new mutable.HashMap[Dag[Project], CompileTraversal]()
    def register(k: Dag[Project], v: CompileTraversal): CompileTraversal = { tasks.put(k, v); v }

    /*
     * [[PartialCompileResult]] is our way to represent errors at the build graph
     * so that we can block the compilation of downstream projects. As we have to
     * abide by this contract because it's used by the pipeline traversal too, we
     * turn an actual compiler failure into a partial failure with a dummy
     * `FailPromise` exception that makes the partial result be recognized as error.
     */
    def toPartialFailure(bundle: CompileBundle, res: Compiler.Result): PartialFailure =
      PartialFailure(bundle.project, CompileExceptions.FailPromise, Task.now(res))

    val es = EmptyIRStore.getStore
    def loop(dag: Dag[Project]): CompileTraversal = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task: Task[Dag[PartialCompileResult]] = dag match {
            case Leaf(project) =>
              val bundleInputs = BundleInputs(project, dag, Map.empty)
              setupAndDeduplicate(client, bundleInputs, computeBundle) { bundle =>
                val cf = new CompletableFuture[IRs]()
                compile(Inputs.normal(bundle, es, cf, emptyOracle, false))
                  .map {
                    case Compiler.Result.Ok(res) =>
                      Leaf(PartialSuccess(bundle, es, JavaCompleted, JavaContinue, Task.now(res)))
                    case res => Leaf(toPartialFailure(bundle, res))
                  }
              }

            case Aggregate(dags) =>
              val downstream = dags.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                Task.now(Parent(PartialEmpty, dagResults))
              }

            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                val failed = dagResults.flatMap(dag => blockedBy(dag).toList)
                if (failed.nonEmpty) {
                  // Register the name of the projects we're blocked on (intransitively)
                  val blocked = Task.now(Compiler.Result.Blocked(failed.map(_.name)))
                  Task.now(Parent(PartialFailure(project, BlockURI, blocked), dagResults))
                } else {
                  val results: List[PartialSuccess] = {
                    val transitive = dagResults.flatMap(Dag.dfs(_)).distinct
                    transitive.collect { case s: PartialSuccess => s }
                  }

                  val projectResults =
                    results.map(ps => ps.result.map(r => ps.bundle.project -> r))
                  // These tasks have already been completing, so collecting results is fast
                  Task.gatherUnordered(projectResults).flatMap { results =>
                    var dependentProducts = new mutable.ListBuffer[(Project, CompileProducts)]()
                    var dependentResults = new mutable.ListBuffer[(File, PreviousResult)]()
                    results.foreach {
                      case (p, s: Compiler.Result.Success) =>
                        val newProducts = s.products
                        dependentProducts.+=(p -> newProducts)
                        val newResult = newProducts.resultForDependentCompilationsInSameRun
                        dependentResults
                          .+=(newProducts.newClassesDir.toFile -> newResult)
                          .+=(newProducts.readOnlyClassesDir.toFile -> newResult)
                      case _ => ()
                    }

                    val cf = new CompletableFuture[IRs]()
                    val resultsMap = dependentResults.toMap
                    val bundleInputs = BundleInputs(project, dag, dependentProducts.toMap)
                    setupAndDeduplicate(client, bundleInputs, computeBundle) { b =>
                      compile(
                        Inputs.normal(b, es, cf, emptyOracle, false, resultsMap)
                      ).map {
                        case Compiler.Result.Ok(res) =>
                          val partial =
                            PartialSuccess(b, es, JavaCompleted, JavaContinue, Task.now(res))
                          Parent(partial, dagResults)
                        case res => Parent(toPartialFailure(b, res), dagResults)
                      }
                    }
                  }
                }
              }
          }
          register(dag, task.memoize)
      }
    }

    loop(dag)
  }

  /**
   * Traverses the dag of projects in such a way that allows compilation pipelining.
   *
   * Note that to use build pipelining, the compilation task needs to have a pipelining
   * implementation where the pickles are generated and the promise in [[Inputs]] completed.
   *
   * @param dag is the dag of projects.
   * @param computeBundle is the function that sets up the project on every node.
   * @param compile is the function that compiles every node, returning a Task.
   * @return A task that returns a dag of compilation results.
   */
  private def pipelineTraversal(
      dag: Dag[Project],
      client: ClientInfo,
      computeBundle: BundleInputs => Task[CompileBundle],
      compile: Inputs => Task[Compiler.Result]
  ): CompileTraversal = {
    val tasks = new scala.collection.mutable.HashMap[Dag[Project], CompileTraversal]()
    def register(k: Dag[Project], v: CompileTraversal): CompileTraversal = { tasks.put(k, v); v }

    val es = EmptyIRStore.getStore
    def loop(dag: Dag[Project]): CompileTraversal = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task = dag match {
            case Leaf(project) =>
              Task.now(new CompletableFuture[IRs]()).flatMap { cf =>
                val bundleInputs = BundleInputs(project, dag, Map.empty)
                setupAndDeduplicate(client, bundleInputs, computeBundle) { bundle =>
                  val jcf = new CompletableFuture[Unit]()
                  val t = compile(Inputs(bundle, es, cf, jcf, JavaContinue, emptyOracle, true))
                  val running =
                    Task.fromFuture(t.executeWithFork.runAsync(ExecutionContext.scheduler))
                  val completeJavaTask = Task
                    .deferFutureAction(jcf.asScala(_))
                    .materialize
                    .map {
                      case Success(_) => JavaSignal.ContinueCompilation
                      case Failure(_) => JavaSignal.FailFastCompilation(bundle.project.name)
                    }
                    .memoize

                  Task
                    .deferFutureAction(c => cf.asScala(c))
                    .materialize
                    .map { irs =>
                      val store = irs.map(irs => SimpleIRStore(Array(irs)))
                      Leaf(PartialCompileResult(bundle, store, jcf, completeJavaTask, running))
                    }
                }
              }

            case Aggregate(dags) =>
              val downstream = dags.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                Task.now(Parent(PartialEmpty, dagResults))
              }

            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                val failed = dagResults.flatMap(dag => blockedBy(dag).toList)
                if (failed.nonEmpty) {
                  // Register the name of the projects we're blocked on (intransitively)
                  val blocked = Task.now(Compiler.Result.Blocked(failed.map(_.name)))
                  Task.now(Parent(PartialFailure(project, BlockURI, blocked), dagResults))
                } else {
                  val directResults =
                    Dag.directDependencies(dagResults).collect { case s: PartialSuccess => s }

                  val results: List[PartialSuccess] = {
                    val transitive = dagResults.flatMap(Dag.dfs(_)).distinct
                    transitive.collect { case s: PartialSuccess => s }
                  }

                  // Passing empty map for dependent classes dirs because we load sigs from memory
                  val bundleInputs = BundleInputs(project, dag, Map.empty)
                  setupAndDeduplicate(client, bundleInputs, computeBundle) { bundle =>
                    // Place IRs stores in same order as classes dirs show up in the raw classpath!
                    val classpath = bundle.project.rawClasspath
                    val indexDirs = classpath.iterator.filter(_.isDirectory).zipWithIndex.toMap
                    val dependencyStore = {
                      val transitive = results.flatMap { r =>
                        val classesDir = client.getUniqueClassesDirFor(r.bundle.project)
                        indexDirs.get(classesDir).iterator.map(i => i -> r.store)
                      }
                      SimpleIRStore(
                        transitive.sortBy(_._1).iterator.flatMap(_._2.getDependentsIRs).toArray
                      )
                    }

                    // Signals whether java compilation can proceed or not
                    val sig = aggregateJavaSignals(results.map(_.javaTrigger))
                    val oracle = new ImmutableCompilerOracle(results)
                    Task.now(new CompletableFuture[IRs]()).flatMap { cf =>
                      val jf = new CompletableFuture[Unit]()
                      val t = compile(Inputs(bundle, dependencyStore, cf, jf, sig, oracle, true))
                      val running = t.executeWithFork.runAsync(ExecutionContext.scheduler)
                      val ongoing = Task.fromFuture(running)
                      val completedJava = {
                        Task
                          .deferFutureAction(jf.asScala(_))
                          .materialize
                          .map {
                            case Success(_) => JavaSignal.ContinueCompilation
                            case Failure(_) => JavaSignal.FailFastCompilation(project.name)
                          }
                      }.memoize // Important to memoize this task for performance reasons

                      Task
                        .deferFutureAction(c => cf.asScala(c))
                        .materialize
                        .map { irs =>
                          val store = irs.map { irs =>
                            dependencyStore.merge(SimpleIRStore(Array(irs)))
                          }

                          Parent(
                            PartialCompileResult(bundle, store, jf, completedJava, ongoing),
                            dagResults
                          )
                        }
                    }
                  }
                }
              }
          }

          register(dag, task.memoize)
      }
    }

    loop(dag)
  }

  private def aggregateJavaSignals(xs: List[Task[JavaSignal]]): Task[JavaSignal] = {
    Task
      .gatherUnordered(xs)
      .map { ys =>
        ys.foldLeft(JavaSignal.ContinueCompilation: JavaSignal) {
          case (JavaSignal.ContinueCompilation, JavaSignal.ContinueCompilation) =>
            JavaSignal.ContinueCompilation
          case (f: JavaSignal.FailFastCompilation, JavaSignal.ContinueCompilation) => f
          case (JavaSignal.ContinueCompilation, f: JavaSignal.FailFastCompilation) => f
          case (JavaSignal.FailFastCompilation(ps), JavaSignal.FailFastCompilation(ps2)) =>
            JavaSignal.FailFastCompilation(ps ::: ps2)
        }
      }
  }
}
