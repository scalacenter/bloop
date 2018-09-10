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
      logger: Logger
  ): CompileTask = {
    val tasks = new scala.collection.mutable.HashMap[Dag[Project], CompileTask]()
    def register(k: Dag[Project], v: CompileTask): CompileTask = { tasks.put(k, v); v }

    def blockedBy(dag: Dag[PartialCompileResult]): Option[Project] = {
      dag match {
        case Leaf(_: PartialSuccess) => None
        case Leaf(result) => Some(result.project)
        case Parent(_: PartialSuccess, _) => None
        case Parent(result, _) => Some(result.project)
      }
    }

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
                  val results = dagResults.flatMap(Dag.dfs(_)).distinct
                  val picklepath = results.flatMap {
                    case s: PartialSuccess => InterfaceUtil.toOption(s.pickleURI)
                    case _: PartialFailure => None
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
