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

import scala.util.{Success, Failure, Try}

object CompileGraph {
  type CompileTask = Task[Dag[PartialCompileResult]]

  case class Inputs(
      project: Project,
      picklepath: List[URI],
      pickleReady: CompletableFuture[Optional[URI]],
      transitiveJavaCompilersCompleted: CompletableFuture[Unit],
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

  private final val JavaContinue = Task.now(JavaSignal.ContinueCompilation)
  private final val NoPickleURI = Failure(CompileExceptions.CompletePromise)
  private final val JavaCompleted = {
    val cf = new CompletableFuture[Unit](); cf.complete(()); cf
  }

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
              compile(Inputs(project, Nil, cf, JavaCompleted, JavaContinue)).map {
                case Compiler.Result.Ok(res) =>
                  Leaf(PartialSuccess(project, Optional.empty(), Nil, JavaContinue, Task.now(res)))
                case res => Leaf(toPartialFailure(project, res))
              }

            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                val failed = dagResults.flatMap(dag => blockedBy(dag).toList)
                if (failed.isEmpty) {
                  val cf = new CompletableFuture[Optional[URI]]()
                  compile(Inputs(project, Nil, cf, JavaCompleted, JavaContinue)).map {
                    case Compiler.Result.Ok(res) =>
                      val noUri = Optional.empty[URI]()
                      val partial =
                        PartialSuccess(project, noUri, Nil, JavaContinue, Task.now(res))
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
                val jcf = new CompletableFuture[Unit]()
                val t = compile(Inputs(project, Nil, cf, jcf, JavaContinue))
                val running =
                  Task.fromFuture(t.executeWithFork.runAsync(ExecutionContext.scheduler))
                val javaSignal = Task.deferFutureAction(jcf.asScala(_)).materialize.map {
                  case Success(_) => JavaSignal.ContinueCompilation
                  case Failure(_) => JavaSignal.FailFastCompilation(List(project.name))
                }

                Task
                  .deferFutureAction(c => cf.asScala(c))
                  .materialize
                  .map(u => Leaf(PartialCompileResult(project, u, Nil, javaSignal, running)))
              }

            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { dagResults =>
                val failed = dagResults.flatMap(dag => blockedBy(dag).toList)
                if (failed.isEmpty) {
                  val results = dagResults.flatMap(Dag.dfs(_)).distinct
                  val picklepath = {
                    results.flatMap {
                      case s: PartialSuccess => InterfaceUtil.toOption(s.pickleURI)
                      case _: PartialFailure => None // It cannot ever happen if `failed.isEmpty`
                    }
                  }

                  // Signals whether java compilation can proceed or not
                  val javaSignal: Task[JavaSignal] = aggregateJavaSignals {
                    results.map {
                      case s: PartialSuccess => s.completeJava
                      // It cannot ever happen if `failed.isEmpty`, so just return dummy value
                      case f: PartialFailure => Task.now(JavaSignal.ContinueCompilation)
                    }
                  }

                  Task.now(new CompletableFuture[Optional[URI]]()).flatMap { cf =>
                    val jcf = new CompletableFuture[Unit]()
                    val t = compile(Inputs(project, picklepath, cf, jcf, javaSignal))
                    val running = t.executeWithFork.runAsync(ExecutionContext.scheduler)
                    val ongoing = Task.fromFuture(running)
                    val completeJavaTask = javaSignal.flatMap {
                      case JavaSignal.ContinueCompilation =>
                        Task.deferFutureAction(jcf.asScala(_)).materialize.map {
                          case Success(_) => JavaSignal.ContinueCompilation
                          case Failure(_) => JavaSignal.FailFastCompilation(List(project.name))
                        }
                      case f @ JavaSignal.FailFastCompilation(_) => Task.now(f)
                    }

                    Task
                      .deferFutureAction(c => cf.asScala(c))
                      .materialize
                      .map { u =>
                        val res = PartialCompileResult(project, u, Nil, completeJavaTask, ongoing)
                        Parent(res, dagResults)
                      }
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
