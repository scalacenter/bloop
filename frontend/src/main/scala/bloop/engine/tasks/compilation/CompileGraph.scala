// scalafmt: { maxColumn = 120 }
package bloop.engine.tasks.compilation

import java.io.File
import java.util.concurrent.CompletableFuture

import bloop.data.Project
import bloop.engine.tasks.compilation.CompileExceptions.BlockURI
import bloop.monix.Java8Compat.JavaCompletableFutureUtils
import bloop.engine._
import bloop.logging.Logger
import bloop.{Compiler, CompilerOracle, JavaSignal, SimpleIRStore}
import monix.eval.Task
import xsbti.compile.{EmptyIRStore, IR, IRStore, PreviousResult}

import scala.util.{Failure, Success}

object CompileGraph {
  type CompileTask = Task[Dag[PartialCompileResult]]

  type IRs = Array[IR]
  case class Inputs(
      bundle: CompileBundle,
      store: IRStore,
      irPromise: CompletableFuture[IRs],
      completeJava: CompletableFuture[Unit],
      transitiveJavaSignal: Task[JavaSignal],
      oracle: CompilerOracle,
      separateJavaAndScala: Boolean,
      dependentResults: Map[File, PreviousResult] = Map.empty
  )

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
      setup: Project => CompileBundle,
      compile: Inputs => Task[Compiler.Result],
      pipeline: Boolean,
      logger: Logger
  ): CompileTask = {
    /* We use different traversals for normal and pipeline compilation because the
     * pipeline traversal has an small overhead (2-3%) for some projects. Check
     * https://benchs.scala-lang.org/dashboard/snapshot/sLrZTBfntTxMWiXJPtIa4DIrmT0QebYF */
    if (pipeline) pipelineTraversal(dag, setup, compile, logger)
    else normalTraversal(dag, setup, compile, logger)
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
            case f: PartialFailure => Some(f.bundle.project)
            case fs: PartialFailures => blockedFromResults(results)
          }
      }
    }

    dag match {
      case Leaf(_: PartialSuccess) => None
      case Leaf(f: PartialFailure) => Some(f.bundle.project)
      case Leaf(fs: PartialFailures) => blockedFromResults(fs.failures)
      case Leaf(PartialEmpty) => None
      case Parent(_: PartialSuccess, _) => None
      case Parent(f: PartialFailure, _) => Some(f.bundle.project)
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

  /**
   * Traverses the dag of projects in a normal way.
   *
   * @param dag is the dag of projects.
   * @param compile is the task we use to compile on every node.
   * @return A task that returns a dag of compilation results.
   */
  private def normalTraversal(
      dag: Dag[Project],
      setup: Project => CompileBundle,
      compile: Inputs => Task[Compiler.Result],
      logger: Logger
  ): CompileTask = {
    val tasks = new scala.collection.mutable.HashMap[Dag[Project], CompileTask]()
    def register(k: Dag[Project], v: CompileTask): CompileTask = { tasks.put(k, v); v }

    /*
     * [[PartialCompileResult]] is our way to represent errors at the build graph
     * so that we can block the compilation of downstream projects. As we have to
     * abide by this contract because it's used by the pipeline traversal too, we
     * turn an actual compiler failure into a partial failure with a dummy
     * `FailPromise` exception that makes the partial result be recognized as error.
     */
    def toPartialFailure(bundle: CompileBundle, res: Compiler.Result): PartialFailure =
      PartialFailure(bundle, CompileExceptions.FailPromise, Task.now(res))

    val es = EmptyIRStore.getStore
    def loop(dag: Dag[Project]): CompileTask = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task: Task[Dag[PartialCompileResult]] = dag match {
            case Leaf(project) =>
              val bundle = setup(project)
              val cf = new CompletableFuture[IRs]()
              compile(Inputs(bundle, es, cf, JavaCompleted, JavaContinue, CompilerOracle.empty, false)).map {
                case Compiler.Result.Ok(res) =>
                  Leaf(PartialSuccess(bundle, es, JavaCompleted, JavaContinue, Task.now(res)))
                case res => Leaf(toPartialFailure(bundle, res))
              }

            case Aggregate(dags) =>
              val downstream = dags.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                val failed = dagResults.flatMap(dag => blockedBy(dag).toList)
                if (failed.isEmpty) Task.now(Parent(PartialEmpty, dagResults))
                else {
                  val failures = Dag.directDependencies(dagResults)
                  // Register the name of the projects we're blocked on (intransitively)
                  val blocked = Task.now(Compiler.Result.Blocked(failed.map(_.name)))
                  Task.now(Parent(PartialFailures(failures, blocked), dagResults))
                }
              }

            case Parent(project, dependencies) =>
              val bundle = setup(project)
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                val failed = dagResults.flatMap(dag => blockedBy(dag).toList)
                if (failed.isEmpty) {
                  val results: List[PartialSuccess] = {
                    val transitive = dagResults.flatMap(Dag.dfs(_)).distinct
                    transitive.collect { case s: PartialSuccess => s }
                  }

                  val projectResults = results.map(ps => ps.result.map(r => ps.bundle.project -> r))
                  // These tasks have already been completing, so collecting its results is super fast
                  Task.gatherUnordered(projectResults).flatMap { results =>
                    val dr = results.collect {
                      case (p, s: Compiler.Result.Success) => p.classesDir.toFile -> s.previous
                    }.toMap

                    val cf = new CompletableFuture[IRs]()
                    compile(Inputs(bundle, es, cf, JavaCompleted, JavaContinue, CompilerOracle.empty, false, dr)).map {
                      case Compiler.Result.Ok(res) =>
                        val partial = PartialSuccess(bundle, es, JavaCompleted, JavaContinue, Task.now(res))
                        Parent(partial, dagResults)
                      case res => Parent(toPartialFailure(bundle, res), dagResults)
                    }
                  }
                } else {
                  // Register the name of the projects we're blocked on (intransitively)
                  val blocked = Task.now(Compiler.Result.Blocked(failed.map(_.name)))
                  Task.now(Parent(PartialFailure(bundle, BlockURI, blocked), dagResults))
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
   * @param setup is the function that sets up the project on every node.
   * @param compile is the function that compiles every node, returning a Task.
   * @return A task that returns a dag of compilation results.
   */
  private def pipelineTraversal(
      dag: Dag[Project],
      setup: Project => CompileBundle,
      compile: Inputs => Task[Compiler.Result],
      logger: Logger
  ): CompileTask = {
    val tasks = new scala.collection.mutable.HashMap[Dag[Project], CompileTask]()
    def register(k: Dag[Project], v: CompileTask): CompileTask = { tasks.put(k, v); v }

    val es = EmptyIRStore.getStore
    def loop(dag: Dag[Project]): CompileTask = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task = dag match {
            case Leaf(project) =>
              Task.now(new CompletableFuture[IRs]()).flatMap { cf =>
                val bundle = setup(project)
                val jcf = new CompletableFuture[Unit]()
                val t = compile(Inputs(bundle, es, cf, jcf, JavaContinue, CompilerOracle.empty, true))
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

            case Aggregate(dags) =>
              val downstream = dags.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                val failed = dagResults.flatMap(dag => blockedBy(dag).toList)
                if (failed.isEmpty) Task.now(Parent(PartialEmpty, dagResults))
                else {
                  val failures = Dag.directDependencies(dagResults)
                  // Register the name of the projects we're blocked on (intransitively)
                  val blocked = Task.now(Compiler.Result.Blocked(failed.map(_.name)))
                  Task.now(Parent(PartialFailures(failures, blocked), dagResults))
                }
              }

            case Parent(project, dependencies) =>
              val bundle = setup(project)
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                val failed = dagResults.flatMap(dag => blockedBy(dag).toList)
                if (failed.isEmpty) {
                  val directResults =
                    Dag.directDependencies(dagResults).collect { case s: PartialSuccess => s }

                  val results: List[PartialSuccess] = {
                    val transitive = dagResults.flatMap(Dag.dfs(_)).distinct
                    transitive.collect { case s: PartialSuccess => s }
                  }

                  // Let's order the IRs exactly in the same order as provided in the classpath!
                  // Required for symbol clashes in dependencies (`AppLoader` in guardian/frontend)
                  val indexDirs = project.compilationClasspath.iterator.filter(_.isDirectory).zipWithIndex.toMap
                  val dependentStore = {
                    val transitiveStores =
                      results.flatMap(r => indexDirs.get(r.bundle.project.classesDir).iterator.map(i => i -> r.store))
                    SimpleIRStore(transitiveStores.sortBy(_._1).iterator.flatMap(_._2.getDependentsIRs).toArray)
                  }

                  // Signals whether java compilation can proceed or not
                  val javaSignal: Task[JavaSignal] =
                    aggregateJavaSignals(results.map(_.javaTrigger))

                  val oracle = CompilerOracle(results.map(r => (r.completeJava, r.bundle.javaSources)))
                  Task.now(new CompletableFuture[IRs]()).flatMap { cf =>
                    val jcf = new CompletableFuture[Unit]()
                    val t = compile(Inputs(bundle, dependentStore, cf, jcf, javaSignal, oracle, true))
                    val running = t.executeWithFork.runAsync(ExecutionContext.scheduler)
                    val ongoing = Task.fromFuture(running)
                    val completeJavaTask = {
                      Task
                        .deferFutureAction(jcf.asScala(_))
                        .materialize
                        .map {
                          case Success(_) => JavaSignal.ContinueCompilation
                          case Failure(_) => JavaSignal.FailFastCompilation(project.name)
                        }
                        .memoize
                    }

                    Task
                      .deferFutureAction(c => cf.asScala(c))
                      .materialize
                      .map { irs =>
                        val store = irs.map(irs => dependentStore.merge(SimpleIRStore(Array(irs))))
                        val res = PartialCompileResult(bundle, store, jcf, completeJavaTask, ongoing)
                        Parent(res, dagResults)
                      }
                  }
                } else {
                  // Register the name of the projects we're blocked on (intransitively)
                  val blocked = Task.now(Compiler.Result.Blocked(failed.map(_.name)))
                  Task.now(Parent(PartialFailure(bundle, BlockURI, blocked), dagResults))
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
