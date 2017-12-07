package bloop.engine.tasks

import bloop.engine.Dag
import bloop.{CompileInputs, Compiler, Project}

import scala.concurrent.duration.Duration
import scala.concurrent.Await
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}
import bloop.reporter.{Reporter, ReporterConfig}
import sbt.internal.inc.{ConcreteAnalysisContents, FileAnalysisStore}

object CompileTasks {
  import bloop.engine.State
  def compile(state: State, project: Project, reporterConfig: ReporterConfig): State = {
    import State.compilerCache
    import state.logger
    def toInputs(project: Project, config: ReporterConfig, result: PreviousResult) = {
      val instance = project.scalaInstance
      val sourceDirs = project.sourceDirectories
      val classpath = project.classpath
      val classesDir = project.classesDir
      val target = project.tmp
      val scalacOptions = project.scalacOptions
      val javacOptions = project.javacOptions
      val reporter = new Reporter(logger, project.baseDirectory.syntax, identity, config)
      // FORMAT: OFF
      CompileInputs(instance, compilerCache, sourceDirs, classpath, classesDir, target, scalacOptions, javacOptions, result, reporter, logger)
      // FORMAT: ON
    }

    val results = new java.util.concurrent.ConcurrentHashMap[Project, PreviousResult]()
    def runCompilation(project: Project): Seq[Project] = {
      val previousResult = state.results
        .getResult(project)
        .getOrElse(sys.error("Results cache was not initialized"))
      val inputs = toInputs(project, reporterConfig, previousResult)
      val result = Compiler.compile(inputs)
      results.put(project, result)
      List(project)
    }

    def compileTask(project: Project) =
      new Task((_: Seq[Project]) => runCompilation(project), () => ())(Mergeable.SeqMergeable)

    import bloop.engine.{Leaf, Parent}
    val visitedTasks = scala.collection.mutable.HashMap[Dag[Project], Task[Seq[Project]]]()
    def constructTaskGraph(dag: Dag[Project]): Task[Seq[Project]] = {
      def createTask: Task[Seq[Project]] = {
        dag match {
          case Leaf(project) => compileTask(project)
          case Parent(project, children) =>
            val childrenCompilations = children.map(constructTaskGraph)
            val parentCompilation = compileTask(project)
            childrenCompilations.foldLeft(parentCompilation) {
              case (task, childrenTask) => task.dependsOn(childrenTask); task
            }
        }
      }

      visitedTasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task = createTask
          visitedTasks.put(dag, task)
          task
      }
    }

    def updateState(state: State): State = {
      import scala.collection.JavaConverters._
      val updatedResults = results.asScala.iterator.foldLeft(state.results) {
        case (results, (project, result)) => results.updateCache(project, result)
      }
      state.copy(results = updatedResults)
    }

    val taskGraph = constructTaskGraph(state.build.getDagFor(project))
    Await.result(taskGraph.run()(state.executionContext), Duration.Inf) match {
      case _: Task.Success[_] => updateState(state)
      case Task.Failure(partial, reasons) =>
        reasons.foreach(throwable => logger.trace(() => throwable))
        updateState(state)
    }
  }

  def clean(state: State, targets: List[Project]): State = {
    val results = state.results
    val newResults = results.reset(state.build.projects)
    state.copy(results = newResults)
  }

  def persist(state: State): State = {
    import state.logger
    import bloop.util.JavaCompat.EnrichOptional
    def persistResult(project: Project, result: PreviousResult): Unit = {
      def toBinaryFile(analysis: CompileAnalysis, setup: MiniSetup): Unit = {
        val storeFile = project.bloopConfigDir.getParent.resolve(s"${project.name}-analysis.bin")
        FileAnalysisStore.binary(storeFile.toFile).set(ConcreteAnalysisContents(analysis, setup))
      }

      val analysis = result.analysis().toOption
      val setup = result.setup().toOption
      (analysis, setup) match {
        case (Some(analysis), Some(setup)) => toBinaryFile(analysis, setup)
        case (Some(analysis), None) =>
          logger.warn(s"$project has analysis but not setup after compilation. Report upstream.")
        case (None, Some(analysis)) =>
          logger.warn(s"$project has setup but not analysis after compilation. Report upstream.")
        case (None, None) => logger.debug(s"Project $project has no analysis file.")
      }

    }

    state.results.iterator.foreach(kv => persistResult(kv._1, kv._2))
    state
  }
}
