package bloop.engine.tasks

import java.nio.file.Files
import java.nio.file.Path

import ch.epfl.scala.debugadapter.testing.TestSuiteEvent

import bloop.bsp.ScalaTestSuites
import bloop.cli.ExitStatus
import bloop.data.JdkConfig
import bloop.data.Project
import bloop.engine.Dag
import bloop.engine.State
import bloop.exec.Forker
import bloop.exec.JvmProcessForker
import bloop.io.AbsolutePath
import bloop.logging.DebugFilter
import bloop.task.Task
import bloop.testing.BloopTestSuiteEventHandler
import bloop.testing.LoggingEventHandler
import bloop.util.JavaCompat.EnrichOptional

import sbt.internal.inc.Analysis
import sbt.internal.inc.AnalyzingCompiler
import sbt.internal.inc.PlainVirtualFileConverter
import sbt.internal.inc.classpath.ClasspathUtil
import sbt.testing._

object Tasks {
  private[bloop] val TestFailedStatus: Set[Status] =
    Set(Status.Failure, Status.Error, Status.Canceled)

  /**
   * Cleans the previous results of the projects specified in `targets`.
   *
   * @param state   The current state of Bloop.
   * @param targets The projects to clean.
   * @param includeDeps Do not run clean for dependencies.
   * @return The new state of Bloop after cleaning.
   */
  def clean(state: State, targets: List[Project], includeDeps: Boolean): Task[State] = {
    val allTargetsToClean =
      if (!includeDeps) targets
      else targets.flatMap(t => Dag.dfs(state.build.getDagFor(t), mode = Dag.PreOrder)).distinct
    state.results.cleanSuccessful(allTargetsToClean.toSet, state.client, state.logger).map {
      newResults =>
        state.copy(results = newResults)
    }
  }

  /**
   * Starts a Scala REPL with the dependencies of `project` on the classpath, including `project`.
   *
   * @param state   The current state of Bloop.
   * @param project The project for which to start the REPL.
   * @return The new state of Bloop.
   */
  def console(
      state: State,
      project: Project
  ): Task[State] = Task {
    import state.logger
    project.scalaInstance match {
      case Some(instance) =>
        val dag = state.build.getDagFor(project)
        val classpath = project.fullRuntimeClasspath(dag, state.client)
        val entries = classpath.map(_.underlying.toFile).toSeq
        logger.debug(s"Setting up the console classpath with ${entries.mkString(", ")}")(
          DebugFilter.All
        )
        val javacBin = project.runtimeJdkConfig.flatMap(_.javacBin)
        val pathEntries = entries.map(e => e.toPath())
        val loader = ClasspathUtil.makeLoader(pathEntries, instance)
        val compiler =
          state.compilerCache
            .get(instance, javacBin, project.javacOptions)
            .scalac
            .asInstanceOf[AnalyzingCompiler]
        val options = project.scalacOptions :+ "-Xnojline"
        val converter = PlainVirtualFileConverter.converter
        // We should by all means add better error handling here!
        compiler.console(
          pathEntries.map(e => converter.toVirtualFile(e)),
          converter,
          options,
          "",
          "",
          state.logger
        )(Some(loader))
      case None => logger.error(s"Missing Scala configuration on project '${project.name}'")
    }

    state
  }

  case class TestRun(project: Project, exitCode: Int, results: List[TestSuiteEvent.Results]) {
    def testsFailed: Boolean =
      results.exists(_.events.exists(e => TestFailedStatus.contains(e.status())))
    def processFailed: Boolean =
      exitCode != 0
    def failed: Option[ExitStatus] =
      if (processFailed || testsFailed) Some(ExitStatus.TestExecutionError) else None
  }

  case class TestRuns(runs: List[TestRun]) {
    def ++(other: TestRuns): TestRuns =
      TestRuns(runs ++ other.runs)
    def status: ExitStatus =
      runs.view.flatMap(_.failed).headOption.getOrElse(ExitStatus.Ok)
  }

  /**
   * Run the tests for all projects in `projectsToTest`.
   *
   * The projects have been already compiled.
   *
   * @param state The current state of Bloop.
   * @param projectsToTest The projects we want to run tests for.
   * @param testFilter A function that filters fully qualified test class names.
   * @return A new state after testing.
   */
  def test(
      state: State,
      projectsToTest: List[Project],
      userTestOptions: List[String],
      testFilter: String => Boolean,
      testClasses: ScalaTestSuites,
      testEventHandler: BloopTestSuiteEventHandler,
      runInParallel: Boolean = false,
      mode: RunMode
  ): Task[TestRuns] = {

    val testTasks = projectsToTest.filter(TestTask.isTestProject).map { project =>
      // note: mutable state here, to collect all `TestSuiteEvent.Results` produced while running tests
      // it should be fine, since it's scoped to this particular evaluation of `TestTask.runTestSuites`
      val resultsBuilder = List.newBuilder[TestSuiteEvent.Results]
      val handleAndStore = new LoggingEventHandler(state.logger) {
        override def report(): Unit = testEventHandler.report()
        override def handle(event: TestSuiteEvent): Unit = {
          testEventHandler.handle(event)
          event match {
            case x: TestSuiteEvent.Results => resultsBuilder += x
            case _ => ()
          }
        }
      }

      val cwd = project.workingDirectory
      TestTask
        .runTestSuites(
          state,
          project,
          cwd,
          userTestOptions,
          testFilter,
          testClasses,
          handleAndStore,
          mode
        )
        .map { exitCode => TestRun(project, exitCode, resultsBuilder.result()) }
    }

    def runAll[A]: List[Task[A]] => Task[List[A]] =
      if (runInParallel) Task.gather else Task.sequence

    runAll(testTasks).map { testRuns =>
      // When the test execution is over report no matter what the result is
      testEventHandler.report()
      TestRuns(testRuns)
    }
  }

  /**
   * Runs the fully qualified class `className` in `project`.
   *
   * @param state     The current state of Bloop.
   * @param project   The project to run.
   * @param cwd       The directory in which to start the forked JVM.
   * @param fqn       The fully qualified name of the main class.
   * @param args      The arguments to pass to the main class. If they contain args
   *                   starting with `-J`, they will be interpreted as jvm options.
   * @param skipJargs Skip the interpretation of `-J` options in `args`.
   * @param mode      The run mode.
   */
  def runJVM(
      state: State,
      project: Project,
      config: JdkConfig,
      cwd: AbsolutePath,
      fqn: String,
      args: Array[String],
      skipJargs: Boolean,
      envVars: List[String],
      mode: RunMode
  ): Task[State] = {
    val (userJvmOptions, userArgs) =
      if (skipJargs) (Array.empty[String], args)
      else args.partition(_.startsWith("-J"))
    val jvmOptions = userJvmOptions.map(_.stripPrefix("-J"))
    runJVM(state, project, config, cwd, fqn, userArgs, jvmOptions, envVars, mode)
  }

  /**
   * Runs the fully qualified class `className` in `project`.
   *
   * @param state           The current state of Bloop.
   * @param project         The project to run.
   * @param cwd             The directory in which to start the forked JVM.
   * @param fqn             The fully qualified name of the main class.
   * @param args            The arguments to pass to the main class.
   * @param jvmOptions      The java options to pass to the jvm.
   * @param mode            The run mode.
   */
  def runJVM(
      state: State,
      project: Project,
      config: JdkConfig,
      cwd: AbsolutePath,
      fqn: String,
      args: Array[String],
      jvmOptions: Array[String],
      envVars: List[String],
      mode: RunMode
  ): Task[State] = {
    val dag = state.build.getDagFor(project)
    val classpath = project.fullRuntimeClasspath(dag, state.client)
    val forker = JvmProcessForker(config, classpath, mode)
    val runTask =
      forker.runMain(cwd, fqn, args, jvmOptions, envVars, state.logger, state.commonOptions)
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
      cwd: AbsolutePath,
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

    state.results.lastSuccessfulResultOrEmpty(project).previous.analysis().toOption match {
      case Some(analysis: Analysis) =>
        val mainClasses = analysis.infos.allInfos.values.flatMap(_.getMainClasses).toList
        logger.debug(s"Found ${mainClasses.size} main classes: ${mainClasses.mkString(", ")}.")(
          DebugFilter.All
        )
        mainClasses
      case _ =>
        logger.debug(
          s"Cannot find main classes in '${project.name}'. No successful compilation detected."
        )(DebugFilter.All)

        Nil
    }
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

}
