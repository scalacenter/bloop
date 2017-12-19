package bloop.engine.tasks

import bloop.engine.Dag
import bloop.{CompileInputs, Compiler, Project}
import bloop.io.AbsolutePath

import scala.concurrent.duration.Duration
import scala.concurrent.Await
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}
import bloop.reporter.{Reporter, ReporterConfig}
import sbt.internal.inc.{AnalyzingCompiler, ConcreteAnalysisContents, FileAnalysisStore}
import sbt.internal.inc.classpath.ClasspathUtilities

object CompileTasks {
  import bloop.engine.State
  private type Results = Map[Project, PreviousResult]
  private val EmptyTask = new Task[Results](identity, () => ())
  def compile(
      state: State,
      project: Project,
      reporterConfig: ReporterConfig,
      excludeRoot: Boolean = false
  ): State = {
    import state.{logger, compilerCache}
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

    def runCompilation(project: Project, rs: Results): Results = {
      val previousResult = state.results.getResult(project)
      val inputs = toInputs(project, reporterConfig, previousResult)
      val result = Compiler.compile(inputs)
      rs + (project -> result)
    }

    import bloop.engine.{Leaf, Parent}
    val visitedTasks = scala.collection.mutable.HashMap[Dag[Project], Task[Results]]()
    def compileTask(project: Project) =
      new Task((rs: Results) => runCompilation(project, rs), () => ())
    def constructTaskGraph(dag: Dag[Project], root: Boolean): Task[Results] = {
      def createTask: Task[Results] = {
        dag match {
          case Leaf(project) if root && excludeRoot => EmptyTask
          case Leaf(project) => compileTask(project)
          case Parent(project, children) =>
            val childrenCompilations = children.map(constructTaskGraph(_, false))
            val parentCompilation = if (root && excludeRoot) EmptyTask else compileTask(project)
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

    def updateState(state: State, results: Results): State = {
      val cache = results.foldLeft(state.results) { case (rs, (p, r)) => rs.updateCache(p, r) }
      state.copy(results = cache)
    }

    val taskGraph = constructTaskGraph(state.build.getDagFor(project), true)
    Await.result(taskGraph.run()(state.executionContext), Duration.Inf) match {
      case Task.Success(results) => updateState(state, results)
      case Task.Failure(partial, reasons) =>
        logger.error(s"Compilation of ${project.name} failed because of the following reasons:")
        reasons.foreach(throwable => logger.trace(() => throwable))
        updateState(state, partial)
    }
  }

  def clean(state: State, targets: List[Project]): State = {
    val results = state.results
    val newResults = results.reset(state.build.projects)
    state.copy(results = newResults)
  }

  def console(state: State, project: Project, config: ReporterConfig, noRoot: Boolean): State = {
    def runConsole(state: State, project: Project, classpath: Array[AbsolutePath]): Unit = {
      val scalaInstance = project.scalaInstance
      val classpathFiles = classpath.map(_.underlying.toFile).toSeq
      state.logger.debug(s"Setting up the console classpath with ${classpathFiles.mkString(", ")}")
      val loader = ClasspathUtilities.makeLoader(classpathFiles, scalaInstance)
      val compiler = state.compilerCache.get(scalaInstance).scalac.asInstanceOf[AnalyzingCompiler]
      val ctxLoader = Thread.currentThread().getContextClassLoader()
      compiler.console(classpathFiles, project.scalacOptions, "", "", state.logger)(Some(loader))
    }

    val newState = compile(state, project, config, excludeRoot = noRoot)
    val classpath = project.classpath
    runConsole(newState, project, classpath)
    newState
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
