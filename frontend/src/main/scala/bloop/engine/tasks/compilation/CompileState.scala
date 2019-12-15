package bloop.engine.tasks.compilation

import monix.eval.Task
import bloop.engine.State
import bloop.data.Project
import bloop.engine.Dag

final class CompileState private (
    val state: State,
    populateProductsMap: Map[Project, Task[Unit]]
) {
  def waitForAllProducts(targetProjects: Seq[Project]): Task[Unit] = {
    def waitForProductsMappedTo(project: Project): Task[Unit] = {
      val allProjects = Dag.dfs(state.build.getDagFor(project))
      Task.sequence(allProjects.flatMap(p => populateProductsMap.get(p))).map(_ => ())
    }

    Task.sequence(targetProjects.map(waitForProductsMappedTo(_))).map(_ => ())
  }
}

object CompileState {
  def apply(state: State, results: List[FinalCompileResult]): CompileState = {
    val populateTasksMap = results.flatMap {
      case FinalEmptyResult => Nil
      case FinalNormalCompileResult(project, result) =>
        val populatingTask = result.successful match {
          case None => Task.unit
          case Some(lastSuccessful) => lastSuccessful.populatingProducts
        }
        List(project -> populatingTask)
    }.toMap
    new CompileState(state, populateTasksMap)
  }
}
