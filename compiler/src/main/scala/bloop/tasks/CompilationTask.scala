package bloop
package tasks

import java.util.Optional

import bloop.util.{Progress, TopologicalSort}
import xsbti.compile.PreviousResult

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

object CompilationTask {

  def apply(project: Project, projects: Map[String, Project], compilerCache: CompilerCache)(
      implicit ec: ExecutionContext): Map[String, Project] = {
    val toCompile = TopologicalSort.reachable(project, projects)

    val progress = new Progress(toCompile.size)
    val tasks =
      toCompile.map {
        case (name, proj) =>
          name -> new Task(
            (projects: Map[String, Project]) => doCompile(proj, projects, compilerCache),
            () => progress.update())
      }

    tasks.foreach {
      case (name, task) =>
        val dependencies = projects(name).dependencies
        dependencies.foreach(dep => task.dependsOn(tasks(dep)))
    }

    val result = Await.result(tasks(project.name).run(), Duration.Inf)
    projects ++ result
  }

  private def doCompile(project: Project,
                        projects: Map[String, Project],
                        compilerCache: CompilerCache): Map[String, Project] = {
    val result = Compiler.compile(project, compilerCache)
    val previousResult =
      PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
    projects ++ Map(project.name -> project.copy(previousResult = previousResult))
  }

}
