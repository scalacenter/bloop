package bloop.engine.tasks

import bloop.cli.ExitStatus
import bloop.engine.{Dag, State}
import bloop.exec.ProcessConfig
import bloop.io.AbsolutePath
import bloop.reporter.{Reporter, ReporterConfig}
import bloop.testing.{DiscoveredTests, TestInternals}
import bloop.{CompileInputs, Compiler, Project}

import java.net.URLClassLoader

import scala.concurrent.duration.Duration
import scala.concurrent.Await

import sbt.internal.inc.{Analysis, AnalyzingCompiler, ConcreteAnalysisContents, FileAnalysisStore}
import sbt.internal.inc.classpath.ClasspathUtilities
import sbt.testing.{Event, EventHandler, Framework, TaskDef, SuiteSelector}
import xsbt.api.Discovery
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}

object Tasks {
  private type Results = Map[Project, PreviousResult]
  private val EmptyTask = new Task[Results](identity, () => ())

  /**
   * Performs incremental compilation of the dependencies of `project`, including `project` if
   * `excludeRoot` is `false`, excluding it otherwise.
   *
   * @param state          The current state of Bloop.
   * @param project        The project to compile.
   * @param reporterConfig Configuration of the compilation messages reporter.
   * @param excludeRoot    If `true`, compile only the dependencies of `project`. Otherwise,
   *                       also compile `project`.
   * @return The new state of Bloop after compilation.
   */
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
        logger.error("Compilation failed because of the following reasons:")
        reasons.foreach(throwable => logger.trace(() => throwable))
        updateState(state, partial)
    }
  }

  /**
   * Cleans the previous results of the projects specified in `targets`.
   *
   * @param state   The current state of Bloop.
   * @param targets The projects to clean.
   * @return The new state of Bloop after cleaning.
   */
  def clean(state: State, targets: List[Project]): State = {
    val results = state.results
    val newResults = results.reset(state.build.projects)
    state.copy(results = newResults)
  }

  /**
   * Starts a Scala REPL with the dependencies of `project` on the classpath, including `project`
   * if `noRoot` is false, excluding it otherwise.
   *
   * @param state   The current state of Bloop.
   * @param project The project for which to start the REPL.
   * @param config  Configuration of the compilation messages reporter.
   * @param noRoot  If false, include `project` on the classpath. Do not include it otherwise.
   * @return The new state of Bloop.
   */
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

  /**
   * Persists on disk the state of the incremental compiler.
   *
   * @param state The current state of Bloop
   * @return The same state, unchanged.
   */
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

  /**
   * Run the tests for `project`, and its dependencies if `aggregate` is set.
   *
   * @param state The current state of Bloop.
   * @param project The project for which to run the tests.
   * @param aggregate If set, also run the tests for the dependencies of `project`. Otherwise,
   *                  run the tests only for `project` otherwise.
   * @return The new state of Bloop.
   */
  def test(state: State, project: Project, aggregate: Boolean): State = {
    // TODO(jvican): This method should cache the test loader always.
    import state.logger
    import bloop.util.JavaCompat.EnrichOptional

    val projectsToTest = if (aggregate) Dag.dfs(state.build.getDagFor(project)) else List(project)
    projectsToTest.foreach { project =>
      val projectName = project.name
      val processConfig = ProcessConfig(project.javaEnv, project.classpath)
      val testLoader = processConfig.toExecutionClassLoader(Some(TestInternals.filteredLoader))
      val frameworks = project.testFrameworks
        .flatMap(fname => TestInternals.getFramework(testLoader, fname.toList, logger))
      logger.debug(s"Found frameworks: ${frameworks.map(_.name).mkString(", ")}")
      val analysis = state.results.getResult(project).analysis().toOption.getOrElse {
        logger.warn(s"Test execution is triggered but no compilation detected for ${projectName}.")
        sbt.internal.inc.Analysis.empty
      }

      val discoveredTests = {
        val tests = discoverTests(analysis, frameworks)
        DiscoveredTests(testLoader, tests)
      }
      val allTestNames: List[String] =
        discoveredTests.tests.valuesIterator
          .flatMap(defs => defs.map(_.fullyQualifiedName()))
          .toList
      logger.debug(s"Bloop found the following tests for ${projectName}: $allTestNames.")
      TestInternals.executeTasks(processConfig, discoveredTests, eventHandler, logger)
    }

    // Return the previous state, test execution doesn't modify it.
    state.mergeStatus(ExitStatus.Ok)
  }

  /**
   * Runs the fully qualified class `className` in `project`.
   *
   * @param state     The current state of Bloop.
   * @param project   The project to run.
   * @param className The fully qualified name of the main class.
   * @param args      The arguments to pass to the main class.
   */
  def run(state: State, project: Project, className: String, args: Array[String]): State = {
    val classpath = project.classpath
    val processConfig = ProcessConfig(project.javaEnv, classpath)
    val exitCode = processConfig.runMain(className, args, state.logger)
    val exitStatus = {
      if (exitCode == ProcessConfig.EXIT_OK) ExitStatus.Ok
      else ExitStatus.UnexpectedError
    }

    state.mergeStatus(exitStatus)
  }

  /**
   * Finds the main classes in `project`.
   *
   * @param state   The current state of Bloop.
   * @param project The project for which to find the main classes.
   * @return An array containing all the main classes that were detected.
   */
  def findMainClasses(state: State, project: Project): Array[String] = {
    import state.logger
    import bloop.util.JavaCompat.EnrichOptional
    val analysis = state.results.getResult(project).analysis().toOption match {
      case Some(analysis: Analysis) => analysis
      case _ =>
        logger.warn(s"`Run` is triggered but no compilation detected from '${project.name}'.")
        sbt.internal.inc.Analysis.empty
    }

    val mainClasses = analysis.infos.allInfos.values.flatMap(_.getMainClasses)
    logger.debug(s"Found ${mainClasses.size} main classes${mainClasses.mkString(": ", ", ", ".")}")
    mainClasses.toArray
  }

  private[bloop] def pickTestProject(projectName: String, state: State): Option[Project] = {
    state.build.getProjectFor(s"$projectName-test").orElse(state.build.getProjectFor(projectName))
  }

  private[bloop] val eventHandler =
    new EventHandler { override def handle(event: Event): Unit = () }

  private[bloop] def discoverTests(analysis: CompileAnalysis,
                                   frameworks: Array[Framework]): Map[Framework, List[TaskDef]] = {
    import scala.collection.mutable
    val (subclassPrints, annotatedPrints) = TestInternals.getFingerprints(frameworks)
    val definitions = TestInternals.potentialTests(analysis)
    val discovered = Discovery(subclassPrints.map(_._1), annotatedPrints.map(_._1))(definitions)
    val tasks = mutable.Map.empty[Framework, mutable.Buffer[TaskDef]]
    frameworks.foreach(tasks(_) = mutable.Buffer.empty)
    discovered.foreach {
      case (defn, discovered) =>
        TestInternals.matchingFingerprints(subclassPrints, annotatedPrints, discovered).foreach {
          case (_, _, framework, fingerprint) =>
            tasks(framework) += new TaskDef(defn.name, fingerprint, false, Array(new SuiteSelector))
        }
    }
    tasks.mapValues(_.toList).toMap
  }

}
