package bloop.engine.tasks

import java.nio.file.{Files, Path}

import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.engine.caches.ResultsCache
import bloop.engine.{Dag, State}
import bloop.exec.Forker
import bloop.io.AbsolutePath
import bloop.logging.LogContext
import bloop.testing.{DiscoveredTests, LoggingEventHandler, TestInternals, TestSuiteEvent, TestSuiteEventHandler}
import bloop.data.Project
import monix.eval.Task
import sbt.internal.inc.{Analysis, AnalyzingCompiler, ConcreteAnalysisContents, FileAnalysisStore}
import sbt.internal.inc.classpath.ClasspathUtilities
import sbt.testing._
import xsbt.api.Discovery
import xsbti.compile.{ClasspathOptionsUtil, CompileAnalysis, MiniSetup, PreviousResult}

object Tasks {
  private val TestFailedStatus: Set[Status] =
    Set(Status.Failure, Status.Error, Status.Canceled)

  /**
   * Cleans the previous results of the projects specified in `targets`.
   *
   * @param state   The current state of Bloop.
   * @param targets The projects to clean.
   * @param includeDeps Do not run clean for dependencies.
   * @return The new state of Bloop after cleaning.
   */
  def clean(state: State, targets: List[Project], includeDeps: Boolean): Task[State] = Task {
    val allTargetsToClean =
      if (!includeDeps) targets
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
   * @param noRoot  If false, include `project` on the classpath. Do not include it otherwise.
   * @return The new state of Bloop.
   */
  def console(
      state: State,
      project: Project,
      noRoot: Boolean
  ): Task[State] = Task {
    import state.logger
    project.scalaInstance match {
      case Some(instance) =>
        val classpath = project.classpath
        val entries = classpath.map(_.underlying.toFile).toSeq
        logger.debug(s"Setting up the console classpath with ${entries.mkString(", ")}")(LogContext.All)
        val loader = ClasspathUtilities.makeLoader(entries, instance)
        val compiler = state.compilerCache.get(instance).scalac.asInstanceOf[AnalyzingCompiler]
        val opts = ClasspathOptionsUtil.repl
        val options = project.scalacOptions :+ "-Xnojline"
        // We should by all means add better error handling here!
        compiler.console(entries, options, opts, "", "", state.logger)(Some(loader))
      case None => logger.error(s"Missing Scala configuration on project '${project.name}'")
    }

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
        val storeFile = project.analysisOut
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
   * @param includeDependencies Run test in the dependencies of `project`.
   * @param testFilter A function from a fully qualified class name to a Boolean, indicating whether
   *                   a test must be included.
   * @return The new state of Bloop.
   */
  def test(
      state: State,
      project: Project,
      cwd: AbsolutePath,
      includeDependencies: Boolean,
      frameworkSpecificRawArgs: List[String],
      testFilter: String => Boolean,
      testEventHandler: TestSuiteEventHandler
  ): Task[State] = {
    import state.logger
    import bloop.util.JavaCompat.EnrichOptional
    implicit val logContext: LogContext = LogContext.Test

    def foundFrameworks(frameworks: List[Framework]) = frameworks.map(_.name).mkString(", ")

    // Test arguments coming after `--` can only be used if only one mapping is found
    def considerFrameworkArgs(frameworks: List[Framework]): List[Config.TestArgument] = {
      if (frameworkSpecificRawArgs.isEmpty) Nil
      else {
        val cls = frameworks.map(f => f.getClass.getName)
        frameworks match {
          case Nil => Nil
          case oneFramework :: Nil =>
            val rawArgs = frameworkSpecificRawArgs
            logger.debug(s"Test options '$rawArgs' assigned to the only found framework $cls'.")
            List(Config.TestArgument(rawArgs, Some(Config.TestFramework(cls))))
          case frameworks =>
            val frameworkNames = foundFrameworks(frameworks)
            val (sysProperties, ignoredArgs) = frameworkSpecificRawArgs.partition(s => s.startsWith("-D"))

            if(sysProperties.isEmpty){
              logger.warn(
                s"Ignored CLI test options '${ignoredArgs}' can only be applied to one framework, found: $frameworkNames")
              Nil
            } else
              List(Config.TestArgument(sysProperties, Some(Config.TestFramework(cls))))
        }
      }
    }

    var failure = false
    val projectsToTest =
      if (!includeDependencies) List(project) else Dag.dfs(state.build.getDagFor(project))
    val testTasks = projectsToTest.map { project =>
      val projectName = project.name
      val forker = Forker(project.javaEnv, project.classpath)
      val testLoader = forker.newClassLoader(Some(TestInternals.filteredLoader))
      val frameworks = project.testFrameworks
        .flatMap(f => TestInternals.loadFramework(testLoader, f.names, logger))
      logger.debug(s"Found frameworks: ${foundFrameworks(frameworks)}")

      val frameworkArgs = considerFrameworkArgs(frameworks)
      val lastCompileResult = state.results.lastSuccessfulResultOrEmpty(project)
      val analysis = lastCompileResult.analysis().toOption.getOrElse {
        logger.warn(s"Test execution is triggered but no compilation detected for ${projectName}.")
        Analysis.empty
      }

      val discovered = {
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

      val opts = state.commonOptions
      val args = project.testOptions.arguments ++ frameworkArgs

      /* Intercept test failures to set the correct error code */
      val failureHandler = new LoggingEventHandler(state.logger) {
        override def report(): Unit = testEventHandler.report()
        override def handle(event: TestSuiteEvent): Unit = {
          testEventHandler.handle(event)
          event match {
            case TestSuiteEvent.Results(_, ev)
                if ev.exists(e => TestFailedStatus.contains(e.status())) =>
              failure = true
            case _ => ()
          }
        }
      }

      logger.debug(s"Running test suites with arguments: $args")
      TestInternals.execute(cwd, forker, discovered, args, failureHandler, logger, opts)
    }

    // For now, test execution is only sequential.
    Task.sequence(testTasks).map { exitCodes =>
      // When the test execution is over report no matter what the result is
      testEventHandler.report()
      logger.debug(s"Test suites failed: $failure")
      val isOk = !failure && exitCodes.forall(_ == 0)
      if (isOk) state.mergeStatus(ExitStatus.Ok)
      else state.copy(status = ExitStatus.TestExecutionError)
    }
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
  def runJVM(state: State,
             project: Project,
             cwd: AbsolutePath,
             fqn: String,
             args: Array[String]): Task[State] = {
    val classpath = project.classpath
    val processConfig = Forker(project.javaEnv, classpath)
    val runTask = processConfig.runMain(cwd, fqn, args, state.logger, state.commonOptions)
    runTask.map { exitCode =>
      val exitStatus = Forker.exitStatus(exitCode)
      state.mergeStatus(exitStatus)
    }
  }

  /**
   * Runs the fully qualified class `className` in a Native or JavaScript `project`.
   *
   * @param state     The current state of Bloop.
   * @param project   The project to run.
   * @param cwd       The directory in which to start the forked run process.
   * @param fqn       The fully qualified name of the main class.
   * @param args      The arguments to pass to the main class.
   */
  def runNativeOrJs(
      state: State,
      project: Project,
      cwd: AbsolutePath,
      fqn: String,
      args: Array[String]
  ): Task[State] = {
    Forker.run(cwd, args, state.logger, state.commonOptions).map { exitCode =>
      val exitStatus = Forker.exitStatus(exitCode)
      state.mergeStatus(exitStatus)
    }
  }

  /**
   * Finds the main classes in `project`.
   *
   * @param state   The current state of Bloop.
   * @param project The project for which to find the main classes.
   * @return An array containing all the main classes that were detected.
   */
  def findMainClasses(state: State, project: Project): List[String] = {
    import state.logger
    import bloop.util.JavaCompat.EnrichOptional

    val analysis = state.results.lastSuccessfulResultOrEmpty(project).analysis().toOption match {
      case Some(analysis: Analysis) => analysis
      case _ =>
        logger.warn(s"`Run` is triggered but no compilation detected from '${project.name}'.")
        Analysis.empty
    }

    val mainClasses = analysis.infos.allInfos.values.flatMap(_.getMainClasses).toList
    logger.debug(s"Found ${mainClasses.size} main classes${mainClasses.mkString(": ", ", ", ".")}")(LogContext.All)
    mainClasses
  }

  def reasonOfInvalidPath(output: Path): Option[String] = {
    if (Files.isDirectory(output))
      Some(s"The output path $output does not point to a file.")
    else if (!Files.isWritable(output.getParent))
      Some(s"The output path ${output.getParent} cannot be created.")
    else None
  }

  def reasonOfInvalidPath(output: Path, extension: String): Option[String] = {
    reasonOfInvalidPath(output).orElse {
      if (!output.toString.endsWith(extension))
        // This is required for the Scala.js linker, otherwise it will throw an exception
        Some(s"The output path $output must have the extension '$extension'.")
      else None
    }
  }

  private[bloop] def pickTestProject(projectName: String, state: State): Option[Project] = {
    state.build.getProjectFor(s"$projectName-test").orElse(state.build.getProjectFor(projectName))
  }

  private[bloop] def discoverTests(
      analysis: CompileAnalysis,
      frameworks: List[Framework]
  ): Map[Framework, List[TaskDef]] = {
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
