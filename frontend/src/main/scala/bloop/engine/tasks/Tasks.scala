package bloop.engine.tasks

import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.engine.caches.ResultsCache
import bloop.engine.{Dag, Leaf, Parent, State}
import bloop.exec.ForkProcess
import bloop.io.AbsolutePath
import bloop.reporter.{Problem, Reporter, ReporterConfig}
import bloop.testing.{DiscoveredTests, TestInternals}
import bloop.{CompileInputs, Compiler, Project}
import monix.eval.Task
import sbt.internal.inc.{Analysis, AnalyzingCompiler, ConcreteAnalysisContents, FileAnalysisStore}
import sbt.internal.inc.classpath.ClasspathUtilities
import sbt.testing.{Event, EventHandler, Framework, SuiteSelector, TaskDef}
import xsbt.api.Discovery
import xsbti.compile.{ClasspathOptionsUtil, CompileAnalysis, MiniSetup, PreviousResult}

object Tasks {
  private val dateFormat = new java.text.SimpleDateFormat("HH:mm:ss.SSS")
  private def currentTime: String = dateFormat.format(new java.util.Date())

  private type CompileResult = (Project, Compiler.Result)
  private type CompileTask = Task[Dag[CompileResult]]

  import scalaz.Show
  private final implicit val showCompileTask: Show[CompileResult] = new Show[CompileResult] {
    private def seconds(ms: Double): String = s"${ms}ms"
    override def shows(r: CompileResult): String = {
      val project = r._1
      r._2 match {
        case Compiler.Result.Empty => s"${project.name} (empty)"
        case Compiler.Result.Cancelled(ms) => s"${project.name} (cancelled, lasted ${ms}ms)"
        case Compiler.Result.Success(_, _, ms) => s"${project.name} (success ${ms}ms)"
        case Compiler.Result.Blocked(on) => s"${project.name} (blocked on ${on.mkString(", ")})"
        case Compiler.Result.Failed(problems, ms) =>
          s"${project.name} (failed with ${Problem.count(problems)}, ${ms}ms)"
      }
    }
  }

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
      val sources = project.sources
      val classpath = project.classpath
      val classesDir = project.classesDir
      val target = project.out
      val scalacOptions = project.scalacOptions
      val javacOptions = project.javacOptions
      val classpathOptions = project.classpathOptions
      val cwd = state.build.origin.getParent
      val reporter = new Reporter(logger, cwd, identity, config)
      // FORMAT: OFF
      CompileInputs(instance, compilerCache, sources, classpath, classesDir, target, scalacOptions, javacOptions, classpathOptions, result, reporter, logger)
      // FORMAT: ON
    }

    def compile(project: Project): Compiler.Result = {
      logger.debug(s"Scheduled compilation of '$project' starting at $currentTime.")
      val previous = state.results.lastSuccessfulResult(project)
      Compiler.compile(toInputs(project, reporterConfig, previous))
    }

    def failed(results: List[CompileResult]): List[Project] = {
      results.collect {
        case (p, _: Compiler.Result.Cancelled) => p
        case (p, _: Compiler.Result.Failed) => p
      }
    }

    val dag = state.build.getDagFor(project)
    toCompileTask(dag, compile(_)).map { results0 =>
      logger.debug(Dag.toDotGraph(results0))
      val results = Dag.dfs(results0)
      val failures = failed(results).distinct
      val newState = state.copy(results = state.results.addResults(results))
      if (failures.isEmpty) newState.copy(status = ExitStatus.Ok)
      else {
        failures.foreach(p => logger.error(s"'${p.name}' failed to compile."))
        newState.copy(status = ExitStatus.CompilationError)
      }
    }
  }

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
      compile: Project => Compiler.Result
  ): CompileTask = {
    val tasks = new scala.collection.mutable.HashMap[Dag[Project], CompileTask]()
    def register(k: Dag[Project], v: CompileTask): CompileTask = { tasks.put(k, v); v }

    def blockedBy(dag: Dag[(Project, Compiler.Result)]): Option[Project] = {
      dag match {
        case Leaf((_, _: Compiler.Result.Success)) => None
        case Leaf((project, _)) => Some(project)
        case Parent((_, _: Compiler.Result.Success), _) => None
        case Parent((project, _), _) => Some(project)
      }
    }

    def loop(dag: Dag[Project]): CompileTask = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task = dag match {
            case Leaf(project) => Task(Leaf(project -> compile(project)))
            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { results =>
                val failed = results.flatMap(dag => blockedBy(dag).toList)
                if (failed.isEmpty) Task(Parent(project -> compile(project), results))
                else {
                  // Register the name of the projects we're blocked on (intransitively)
                  val blocked = Compiler.Result.Blocked(failed.map(_.name))
                  Task.now(Parent(project -> blocked, results))
                }
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
    val newResults = state.results.cleanSuccessful(allTargetsToClean)
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
   * Persists every analysis file (the state of the incremental compiler) on disk in parallel.
   *
   * @param state The current state of Bloop
   * @return The task that will persist all the results in parallel.
   */
  def persist(state: State): Task[Unit] = {
    val out = state.commonOptions.ngout
    import bloop.util.JavaCompat.EnrichOptional
    def persist(project: Project, result: PreviousResult): Unit = {
      def toBinaryFile(analysis: CompileAnalysis, setup: MiniSetup): Unit = {
        val storeFile = project.out.resolve(s"${project.name}-analysis.bin")
        state.commonOptions.ngout.println(s"Writing ${storeFile.syntax}.")
        FileAnalysisStore.binary(storeFile.toFile).set(ConcreteAnalysisContents(analysis, setup))
        ResultsCache.persisted.add(result)
        ()
      }

      val analysis = result.analysis().toOption
      val setup = result.setup().toOption
      (analysis, setup) match {
        case (Some(analysis), Some(setup)) =>
          if (ResultsCache.persisted.contains(result)) ()
          else toBinaryFile(analysis, setup)
        case (Some(analysis), None) =>
          out.println(s"$project has analysis but not setup after compilation. Report upstream.")
        case (None, Some(analysis)) =>
          out.println(s"$project has setup but not analysis after compilation. Report upstream.")
        case (None, None) => out.println(s"Project $project has no analysis and setup.")
      }
    }

    val ts = state.results.allSuccessful.map { case (p, result) => Task(persist(p, result)) }
    Task.gatherUnordered(ts).map(_ => ())
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
  def test(
      state: State,
      project: Project,
      cwd: AbsolutePath,
      isolated: Boolean,
      frameworkSpecificRawArgs: List[String],
      testFilter: String => Boolean
  ): Task[State] = Task {
    // TODO(jvican): This method should cache the test loader always.
    import state.logger
    import bloop.util.JavaCompat.EnrichOptional

    val projectsToTest = if (isolated) List(project) else Dag.dfs(state.build.getDagFor(project))
    projectsToTest.foreach { project =>
      val projectName = project.name
      val projectTestArgs = project.testOptions.arguments
      val forkProcess = ForkProcess(project.javaEnv, project.classpath)
      val testLoader = forkProcess.toExecutionClassLoader(Some(TestInternals.filteredLoader))
      val frameworks = project.testFrameworks
        .flatMap(f => TestInternals.loadFramework(testLoader, f.names, logger))
      def foundFrameworks = frameworks.map(_.name).mkString(", ")
      logger.debug(s"Found frameworks: $foundFrameworks")

      // Test arguments coming after `--` can only be used if only one mapping is found
      val frameworkArgs = {
        if (frameworkSpecificRawArgs.isEmpty) Nil
        else {
          frameworks match {
            case Array(oneFramework) =>
              val rawArgs = frameworkSpecificRawArgs.toArray
              val cls = oneFramework.getClass.getName()
              logger.debug(s"Test options '$rawArgs' assigned to the only found framework $cls'.")
              List(Config.TestArgument(rawArgs, Some(Config.TestFramework(List(cls)))))
            case _ =>
              val ignoredArgs = frameworkSpecificRawArgs.mkString(" ")
              logger.warn(
                s"Framework-specific test options '${ignoredArgs}' are ignored because several frameworks were found: $foundFrameworks")
              Nil
          }
        }
      }

      val analysis = state.results.lastSuccessfulResult(project).analysis().toOption.getOrElse {
        logger.warn(s"Test execution is triggered but no compilation detected for ${projectName}.")
        Analysis.empty
      }

      val discoveredTests = {
        val tests = discoverTests(analysis, frameworks)
        val excluded = project.testOptions.excludes.toSet
        val ungroupedTests = tests.toList.flatMap {
          case (framework, tasks) => tasks.map(t => (framework, t))
        }
        val (includedTests, excludedTests) = ungroupedTests.partition {
          case (_, task) =>
            val fqn = task.fullyQualifiedName()
            !excluded(fqn) && testFilter(fqn)
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

      val args = project.testOptions.arguments ++ frameworkArgs
      val env = state.commonOptions.env
      TestInternals.executeTasks(cwd, forkProcess, discoveredTests, args, handler, logger, env)
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
    val exitCode = processConfig.runMain(cwd, fqn, args, state.logger, state.commonOptions.env)
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
    val analysis = state.results.lastSuccessfulResult(project).analysis().toOption match {
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

  private[bloop] val handler =
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
