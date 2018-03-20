package bloop.engine.tasks

import bloop.cli.ExitStatus
import bloop.engine.{Dag, Leaf, Parent, State}
import bloop.exec.ForkProcess
import bloop.io.AbsolutePath
import bloop.reporter.{Reporter, ReporterConfig}
import bloop.testing.{DiscoveredTests, TestInternals}
import bloop.{CompileInputs, Compiler, Project}
import monix.eval.Task

import sbt.internal.inc.{Analysis, AnalyzingCompiler, ConcreteAnalysisContents, FileAnalysisStore}
import sbt.internal.inc.classpath.ClasspathUtilities
import sbt.testing.{Event, EventHandler, Framework, SuiteSelector, TaskDef}
import xsbt.api.Discovery
import xsbti.compile.{ClasspathOptionsUtil, CompileAnalysis, MiniSetup, PreviousResult}

object Tasks {
  object Compilation {
    sealed trait Result
    case class Blocked(project: Project) extends Result
    case class Cancelled(project: Project) extends Result
    case class Failed(project: Project, problems: List[xsbti.Problem]) extends Result
    case class Success(project: Project, result: PreviousResult) extends Result

    object Result {
      implicit val showResult: scalaz.Show[Result] = new scalaz.Show[Result] {
        override def shows(r: Result): String = r match {
          case Blocked(project) => s"${project.name} (blocked)"
          case Cancelled(project) => s"${project.name} (cancelled)"
          case Failed(project, _) => s"${project.name} (failed)"
          case Success(project, _) => s"${project.name} (success)"
        }
      }

      def failedProjects(results: List[Result]): List[Project] =
        results.collect { case r: Cancelled => r.project; case r: Failed => r.project }

      def successfulProjects(results: List[Result]): List[(Project, PreviousResult)] =
        results.collect { case Success(p, r) => (p, r) }
    }
  }

  private val dateFormat = new java.text.SimpleDateFormat("HH:mm:ss.SSS")
  private def currentTime: String = dateFormat.format(new java.util.Date())

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
  ): Task[State] = {
    import state.{logger, compilerCache}
    def toInputs(project: Project, config: ReporterConfig, result: PreviousResult) = {
      val instance = project.scalaInstance
      val sourceDirs = project.sourceDirectories
      val classpath = project.classpath
      val classesDir = project.classesDir
      val target = project.tmp
      val scalacOptions = project.scalacOptions
      val javacOptions = project.javacOptions
      val classpathOptions = ClasspathOptionsUtil.boot()
      val cwd = state.build.origin.getParent
      val reporter = new Reporter(logger, cwd, identity, config)
      // FORMAT: OFF
      CompileInputs(instance, compilerCache, sourceDirs, classpath, classesDir, target, scalacOptions, javacOptions, classpathOptions, result, reporter, logger)
      // FORMAT: ON
    }

    def compile(project: Project): Compilation.Result = {
      logger.debug(s"Scheduled compilation of '$project' starting at $currentTime.")
      val previous = state.results.getResult(project)
      val inputs = toInputs(project, reporterConfig, previous)
      try Compilation.Success(project, Compiler.compile(inputs))
      catch {
        case f: xsbti.CompileFailed => Compilation.Failed(project, f.problems().toList)
        case _: xsbti.CompileCancelled => Compilation.Cancelled(project)
      }
    }

    val dag = state.build.getDagFor(project)
    toCompileTask(dag, compile(_)).map { results0 =>
      logger.debug(Dag.toDotGraph(results0))
      val results = Dag.dfs(results0)
      val failures = Compilation.Result.failedProjects(results).distinct
      val successes = Compilation.Result.successfulProjects(results)
      val newState = state.copy(results = state.results.addResults(successes))
      if (failures.isEmpty) newState
      else {
        failures.foreach(p => logger.error(s"'${p.name}' failed to compile."))
        newState.copy(status = ExitStatus.CompilationError)
      }
    }
  }

  private type CompileTask = Task[Dag[Compilation.Result]]

  /**
   * Turns a dag of projects into a task that returns a dag of compilation results
   * that can then be used to debug the evaluation of the compilation within Monix
   * and access the compilation results received from Zinc.
   *
   * @param dag The dag of projects to be compiled.
   * @return A task that returns a dag of compilation results.
   */
  private def toCompileTask(
      dag: Dag[Project],
      compile: Project => Compilation.Result
  ): CompileTask = {
    val tasks = new scala.collection.mutable.HashMap[Dag[Project], CompileTask]()
    def register(k: Dag[Project], v: CompileTask): CompileTask = { tasks.put(k, v); v }

    def propagate(dag: Dag[Compilation.Result]): Boolean = {
      dag match {
        case Leaf(_: Compilation.Success) => true
        case Leaf(_) => false
        case Parent(_: Compilation.Success, _) => true
        case Parent(_, _) => false
      }
    }

    def loop(dag: Dag[Project]): CompileTask = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task = dag match {
            case Leaf(project) => Task(Leaf(compile(project)))
            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { results =>
                val stopNow = results.exists(result => !propagate(result))
                if (stopNow) Task.now(Parent(Compilation.Blocked(project), results))
                else Task(Parent(compile(project), results))
              }
          }
          register(dag, task.memoize)
      }
    }

    loop(dag)
  }

  /**
   * Cleans the previous results of the projects specified in `targets`.
   *
   * @param state   The current state of Bloop.
   * @param targets The projects to clean.
   * @param isolated Do not run clean for dependencies.
   * @return The new state of Bloop after cleaning.
   */
  def clean(state: State, targets: List[Project], isolated: Boolean): Task[State] = Task {
    val allTargetsToClean =
      if (isolated) targets
      else targets.flatMap(t => Dag.dfs(state.build.getDagFor(t))).distinct
    val results = state.results
    val newResults = results.reset(allTargetsToClean)
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
  def console(state: State,
              project: Project,
              config: ReporterConfig,
              noRoot: Boolean): Task[State] = Task {
    val scalaInstance = project.scalaInstance
    val classpath = project.classpath
    val classpathFiles = classpath.map(_.underlying.toFile).toSeq
    state.logger.debug(s"Setting up the console classpath with ${classpathFiles.mkString(", ")}")
    val loader = ClasspathUtilities.makeLoader(classpathFiles, scalaInstance)
    val compiler = state.compilerCache.get(scalaInstance).scalac.asInstanceOf[AnalyzingCompiler]
    val classpathOptions = ClasspathOptionsUtil.repl
    compiler.console(classpathFiles, project.scalacOptions, classpathOptions, "", "", state.logger)(
      Some(loader))
    state
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
   * Run the tests for `project` and its dependencies (optional).
   *
   * @param state The current state of Bloop.
   * @param project The project for which to run the tests.
   * @param cwd      The directory in which to start the forked JVM.
   * @param isolated Do not run the tests for the dependencies of `project`.
   * @param testFilter A function from a fully qualified class name to a Boolean, indicating whether
   *                   a test must be included.
   * @return The new state of Bloop.
   */
  def test(state: State,
           project: Project,
           cwd: AbsolutePath,
           isolated: Boolean,
           testFilter: String => Boolean): Task[State] = Task {
    // TODO(jvican): This method should cache the test loader always.
    import state.logger
    import bloop.util.JavaCompat.EnrichOptional

    val projectsToTest = if (isolated) List(project) else Dag.dfs(state.build.getDagFor(project))
    projectsToTest.foreach { project =>
      val projectName = project.name
      val processConfig = ForkProcess(project.javaEnv, project.classpath)
      val testLoader = processConfig.toExecutionClassLoader(Some(TestInternals.filteredLoader))
      val frameworks = project.testFrameworks
        .flatMap(fname => TestInternals.getFramework(testLoader, fname.toList, logger))
      logger.debug(s"Found frameworks: ${frameworks.map(_.name).mkString(", ")}")
      val analysis = state.results.getResult(project).analysis().toOption.getOrElse {
        logger.warn(s"Test execution is triggered but no compilation detected for ${projectName}.")
        Analysis.empty
      }

      val discoveredTests = {
        val tests = discoverTests(analysis, frameworks)
        val ungroupedTests = tests.toList.flatMap {
          case (framework, tasks) => tasks.map(t => (framework, t))
        }
        val (includedTests, excludedTests) = ungroupedTests.partition {
          case (framework, task) => testFilter(task.fullyQualifiedName)
        }

        if (logger.isVerbose) {
          val allNames = ungroupedTests.map(_._2.fullyQualifiedName).mkString(", ")
          val includedNames = includedTests.map(_._2.fullyQualifiedName).mkString(", ")
          val excludedNames = excludedTests.map(_._2.fullyQualifiedName).mkString(", ")
          logger.debug(s"Bloop found the following tests for $projectName: $allNames")
          logger.debug(s"The following tests were included by the filter: $includedNames")
          logger.debug(s"The following tests were excluded by the filter: $excludedNames")
        }

        DiscoveredTests(testLoader, includedTests.groupBy(_._1).mapValues(_.map(_._2)))
      }

      TestInternals.executeTasks(cwd, processConfig, discoveredTests, eventHandler, logger)
    }

    // Return the previous state, test execution doesn't modify it.
    state.mergeStatus(ExitStatus.Ok)
  }

  /**
   * Runs the fully qualified class `className` in `project`.
   *
   * @param state     The current state of Bloop.
   * @param project   The project to run.
   * @param cwd       The directory in which to start the forked JVM.
   * @param fqn       The fully qualified name of the main class.
   * @param args      The arguments to pass to the main class.
   */
  def run(state: State,
          project: Project,
          cwd: AbsolutePath,
          fqn: String,
          args: Array[String]): Task[State] = Task {
    val classpath = project.classpath
    val processConfig = ForkProcess(project.javaEnv, classpath)
    val exitCode = processConfig.runMain(cwd, fqn, args, state.logger)
    val exitStatus = {
      if (exitCode == ForkProcess.EXIT_OK) ExitStatus.Ok
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
        Analysis.empty
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
