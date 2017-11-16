package bloop
package tasks

import java.util.Optional

import bloop.util.{Progress, TopologicalSort}

import xsbti.Logger
import xsbti.compile.PreviousResult

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

object CompilationTask {

  def sequential(project: Project,
                 projects: Map[String, Project],
                 compilerCache: CompilerCache,
                 logger: Logger)(implicit ec: ExecutionContext): Map[String, Project] = {
    val progress = new Progress
    val subTasks: Map[String, Task[Map[String, Project]]] =
      getTasks(project, projects, compilerCache, progress, logger)
    subTasks.toSeq.sliding(2).foreach {
      case Seq((_, fst), (_, snd)) => snd.dependsOn(fst)
      case single                  => ()
    }
    projects ++ execute(subTasks(project.name), logger)
  }

  def parallel(project: Project,
               projects: Map[String, Project],
               compilerCache: CompilerCache,
               logger: Logger)(implicit ec: ExecutionContext): Map[String, Project] = {
    val progress = new Progress
    val subTasks = getTasks(project, projects, compilerCache, progress, logger)
    subTasks.foreach {
      case (name, task) =>
        val dependencies = projects(name).dependencies
        dependencies.foreach(dep => task.dependsOn(subTasks(dep)))
    }
    projects ++ execute(subTasks(project.name), logger)
  }

  def parallelNaive(project: Project,
                    projects: Map[String, Project],
                    compilerCache: CompilerCache,
                    logger: Logger)(implicit ec: ExecutionContext): Map[String, Project] = {
    val progress = new Progress
    val steps    = TopologicalSort.tasks(project, projects)

    progress.setTotal(steps.flatten.size)

    val changedProjects =
      for {
        tasks   <- steps
        project <- tasks.par
      } yield execute(getTask(project, projects, compilerCache, progress, logger), logger)

    val mergeable = implicitly[Mergeable[Map[String, Project]]]
    projects ++ mergeable.merge(changedProjects).toMap
  }

  private def execute(task: Task[Map[String, Project]], logger: Logger)(
      implicit ec: ExecutionContext): Map[String, Project] =
    Await.result(task.run(), Duration.Inf) match {
      case Task.Success(result) =>
        result
      case Task.Failure(partial, reasons) =>
        reasons.foreach { throwable =>
          logger.trace(() => throwable)
        }
        partial
    }

  private def getTasks(project: Project,
                       projects: Map[String, Project],
                       compilerCache: CompilerCache,
                       progress: Progress,
                       logger: Logger): Map[String, Task[Map[String, Project]]] = {
    val toCompile = TopologicalSort.reachable(project, projects)
    progress.setTotal(toCompile.size)
    toCompile.map {
      case (name, proj) =>
        name -> getTask(project, projects, compilerCache, progress, logger)
    }
  }

  private def getTask(project: Project,
                      projects: Map[String, Project],
                      compilerCache: CompilerCache,
                      progress: Progress,
                      logger: Logger) = {
    new Task(
      (projects: Map[String, Project]) => doCompile(project, projects, compilerCache, logger),
      () => progress.update())
  }

  private def doCompile(project: Project,
                        projects: Map[String, Project],
                        compilerCache: CompilerCache,
                        logger: Logger): Map[String, Project] = {
    val inputs = toCompileInputs(project, compilerCache, logger)
    val result = Compiler.compile(inputs)
    val previousResult =
      PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
    projects ++ Map(project.name -> project.copy(previousResult = previousResult))
  }

  def toCompileInputs(project: Project, cache: CompilerCache, logger: Logger): CompileInputs = {
    val instance   = project.scalaInstance
    val sourceDirs = project.sourceDirectories
    val classpath  = project.classpath
    val classesDir = project.classesDir
    val target     = project.tmp
    val previous   = project.previousResult
    CompileInputs(instance, cache, sourceDirs, classpath, classesDir, target, previous, logger)
  }
}
