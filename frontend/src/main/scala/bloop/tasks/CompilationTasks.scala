package bloop.tasks

import java.util.Optional

import bloop.engine.ExecutionContext
import bloop.{CompileInputs, Compiler, CompilerCache, Project}

import scala.concurrent.duration.Duration
import scala.concurrent.Await
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}
import bloop.logging.Logger
import bloop.reporter.{Reporter, ReporterConfig}
import bloop.util.TopologicalSort
import sbt.internal.inc.{ConcreteAnalysisContents, FileAnalysisStore}

case class CompilationTasks(initialProjects: Map[String, Project],
                            cache: CompilerCache,
                            logger: Logger) {
  private final val EmptyCompileResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])

  def clean(projectNames: List[String]): Map[String, Project] = {
    initialProjects.filterKeys(projectNames.contains).mapValues { p =>
      p.copy(previousResult = EmptyCompileResult)
    }
  }

  def parallelCompile(project: Project, reporterConfig: ReporterConfig)(
      implicit ec: ExecutionContext): Map[String, Project] = {
    val subTasks = getTasks(project, reporterConfig)
    subTasks.foreach {
      case (name, task) =>
        val dependencies = initialProjects(name).dependencies
        dependencies.foreach(dep => task.dependsOn(subTasks(dep)))
    }
    initialProjects ++ execute(subTasks(project.name), logger)
  }

  private def execute(task: Task[Map[String, Project]], logger: Logger)(
      implicit ec: ExecutionContext): Map[String, Project] = {
    Await.result(task.run(), Duration.Inf) match {
      case Task.Success(result) => result
      case Task.Failure(partial, reasons) =>
        reasons.foreach(throwable => logger.trace(() => throwable))
        partial
    }
  }

  private def getTasks(project: Project,
                       reporterConfig: ReporterConfig): Map[String, Task[Map[String, Project]]] = {
    val toCompile = TopologicalSort.reachable(project, initialProjects)
    toCompile.map {
      case (name, proj) => name -> getTask(proj, reporterConfig)
    }
  }

  private def getTask(project: Project,
                      reporterConfig: ReporterConfig): Task[Map[String, Project]] = {
    new Task(projects => doCompile(projects, project, reporterConfig), () => ())
  }

  private def doCompile(previousProjects: Map[String, Project],
                        project: Project,
                        reporterConfig: ReporterConfig): Map[String, Project] = {
    val inputs = toCompileInputs(project, reporterConfig)
    val result = Compiler.compile(inputs)
    val previousResult =
      PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
    previousProjects ++ Map(project.name -> project.copy(previousResult = previousResult))
  }

  private def toCompileInputs(project: Project, reporterConfig: ReporterConfig): CompileInputs = {
    val instance = project.scalaInstance
    val sourceDirs = project.sourceDirectories
    val classpath = project.classpath
    val classesDir = project.classesDir
    val target = project.tmp
    val scalacOptions = project.scalacOptions
    val javacOptions = project.javacOptions
    val previous = project.previousResult
    val reporter = new Reporter(logger, project.baseDirectory.syntax, identity, reporterConfig)
    CompileInputs(instance,
                  cache,
                  sourceDirs,
                  classpath,
                  classesDir,
                  target,
                  scalacOptions,
                  javacOptions,
                  previous,
                  reporter,
                  logger)
  }
}

object CompilationTasks {
  def persistAnalysis(project: Project, logger: Logger): Unit = {
    import bloop.util.JavaCompat.EnrichOptional
    val previousResult = project.previousResult
    (previousResult.analysis().toOption, previousResult.setup().toOption) match {
      case (Some(analysis), Some(setup)) =>
        project.origin match {
          case Some(origin) =>
            val storeFile = origin.getParent.resolve(s"${project.name}-analysis.bin").toFile
            FileAnalysisStore.binary(storeFile).set(ConcreteAnalysisContents(analysis, setup))
          case None => logger.warn(s"Missing target directory for ${project.name}.")
        }
      case _ => logger.debug(s"Project ${project.name} has no analysis file.")
    }
  }
}