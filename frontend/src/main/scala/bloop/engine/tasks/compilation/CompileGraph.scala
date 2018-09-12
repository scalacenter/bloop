package bloop.engine.tasks.compilation

import java.net.URI
import java.util.Optional
import java.util.concurrent.CompletableFuture

import bloop.engine.tasks.compilation.CompileExceptions.BlockURI
import bloop.monix.Java8Compat.JavaCompletableFutureUtils
import bloop.engine.{Dag, ExecutionContext, Leaf, Parent}
import bloop.logging.Logger
import bloop.{Compiler, Project}
import monix.eval.Task
import sbt.internal.inc.bloop.JavaSignal
import sbt.util.InterfaceUtil

object CompileGraph {
  type CompileTask = Task[Dag[PartialCompileResult]]

  case class Inputs(
      project: Project,
      picklepath: List[URI],
      pickleReady: CompletableFuture[Optional[URI]],
      javaSignal: Task[JavaSignal]
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
      compile: Inputs => Task[Compiler.Result],
      pipeline: Boolean,
      logger: Logger
  ): CompileTask = {
    /* We use different traversals for normal and pipeline compilation because the
     * pipeline traversal has an small overhead (2-3%) for some projects. Check
     * https://benchs.scala-lang.org/dashboard/snapshot/sLrZTBfntTxMWiXJPtIa4DIrmT0QebYF */
    if (pipeline) pipelineTraversal(dag, compile, logger)
    else normalTraversal(dag, compile, logger)
  }

  private final val ContinueJavaCompilation = Task.now(JavaSignal.ContinueCompilation)
  private final val NoPickleURI = scala.util.Failure(CompileExceptions.CompletePromise)

  private def blockedBy(dag: Dag[PartialCompileResult]): Option[Project] = {
    dag match {
      case Leaf(_: PartialSuccess) => None
      case Leaf(result) => Some(result.project)
      case Parent(_: PartialSuccess, _) => None
      case Parent(result, _) => Some(result.project)
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
    def toPartialFailure(project: Project, res: Compiler.Result): PartialFailure =
      PartialFailure(project, CompileExceptions.FailPromise, Nil, Task.now(res))

    def loop(dag: Dag[Project]): CompileTask = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task: Task[Dag[PartialCompileResult]] = dag match {
            case Leaf(project) =>
              val cf = new CompletableFuture[Optional[URI]]()
              compile(Inputs(project, Nil, cf, ContinueJavaCompilation)).map {
                case Compiler.Result.Ok(res) =>
                  Leaf(PartialSuccess(project, Optional.empty(), Nil, Task.now(res)))
                case res => Leaf(toPartialFailure(project, res))
              }

            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                val failed = dagResults.flatMap(dag => blockedBy(dag).toList)
                if (failed.isEmpty) {
                  val cf = new CompletableFuture[Optional[URI]]()
                  compile(Inputs(project, Nil, cf, ContinueJavaCompilation)).map {
                    case Compiler.Result.Ok(res) =>
                      val partial = PartialSuccess(project, Optional.empty(), Nil, Task.now(res))
                      Parent(partial, dagResults)
                    case res => Parent(toPartialFailure(project, res), dagResults)
                  }
                } else {
                  // Register the name of the projects we're blocked on (intransitively)
                  val blocked = Task.now(Compiler.Result.Blocked(failed.map(_.name)))
                  Task.now(Parent(PartialFailure(project, BlockURI, Nil, blocked), dagResults))
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
   * @param compile is the task we use to compile on every node.
   * @return A task that returns a dag of compilation results.
   */
  private def pipelineTraversal(
      dag: Dag[Project],
      compile: Inputs => Task[Compiler.Result],
      logger: Logger
  ): CompileTask = {
    val tasks = new scala.collection.mutable.HashMap[Dag[Project], CompileTask]()
    def register(k: Dag[Project], v: CompileTask): CompileTask = { tasks.put(k, v); v }

    def loop(dag: Dag[Project]): CompileTask = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task = dag match {
            case Leaf(project) =>
              Task.now(new CompletableFuture[Optional[URI]]()).flatMap { cf =>
                val t =
                  compile(Inputs(project, Nil, cf, Task.now(JavaSignal.ContinueCompilation)))
                val running = t.executeWithFork.runAsync(ExecutionContext.scheduler)
                Task
                  .deferFutureAction(c => cf.asScala(c))
                  .materialize
                  .map(u => Leaf(PartialCompileResult(project, u, Nil, Task.fromFuture(running))))
              }

            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                val failed = dagResults.flatMap(dag => blockedBy(dag).toList)
                if (failed.isEmpty) {
                  val picklepath = {
                    val results = dagResults.flatMap(Dag.dfs(_)).distinct
                    results.flatMap {
                      case s: PartialSuccess => InterfaceUtil.toOption(s.pickleURI)
                      case _: PartialFailure => None
                    }
                  }

                  Task.now(new CompletableFuture[Optional[URI]]()).flatMap { cf =>
                    // Signals whether Java compilation can proceed or not.
                    val javaSignal = Task.now(JavaSignal.ContinueCompilation)
                    /*                    val javaSignal = {
                      Task
                        .gatherUnordered(dfss.map(t => t._2.result.map(r => t._1 -> r)))
                        .map { rs =>
                          val projects = rs.collect { case (p, Compiler.Result.NotOk(_)) => p.name }
                          if (projects.isEmpty) JavaSignal.ContinueCompilation
                          else JavaSignal.FailFastCompilation(projects)
                        }
                    }*/

                    val t = compile(Inputs(project, picklepath, cf, javaSignal))
                    val running = t.executeWithFork.runAsync(ExecutionContext.scheduler)
                    val futureRunning = Task.fromFuture(running)
                    Task
                      .deferFutureAction(c => cf.asScala(c))
                      .materialize
                      .map(u =>
                        Parent(PartialCompileResult(project, u, Nil, futureRunning), dagResults))
                  }
                } else {
                  // Register the name of the projects we're blocked on (intransitively)
                  val blocked = Task.now(Compiler.Result.Blocked(failed.map(_.name)))
                  Task.now(Parent(PartialFailure(project, BlockURI, Nil, blocked), dagResults))
                }
              }
          }
          register(dag, task.memoize)
      }
    }

    loop(dag)
  }
}
