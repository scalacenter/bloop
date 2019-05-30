package bloop.engine.tasks

import java.nio.file.{Files, Path}

import bloop.cli.ExitStatus
import bloop.engine.caches.ResultsCache
import bloop.logging.DebugFilter
import bloop.data.Project
import bloop.engine.{Dag, State}
import bloop.exec.{Forker, JavaEnv, JvmProcessForker}
import bloop.io.AbsolutePath
import bloop.util.JavaCompat.EnrichOptional
import bloop.testing.{LoggingEventHandler, TestSuiteEvent, TestSuiteEventHandler}
import monix.eval.Task
import sbt.internal.inc.{Analysis, AnalyzingCompiler, ConcreteAnalysisContents, FileAnalysisStore}
import sbt.internal.inc.classpath.ClasspathUtilities
import sbt.testing._
import xsbti.compile.{ClasspathOptionsUtil, CompileAnalysis, MiniSetup, PreviousResult}

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
        val dag = state.build.getDagFor(project)
        val classpath = project.fullClasspath(dag, state.client)
        val entries = classpath.map(_.underlying.toFile).toSeq
        logger.debug(s"Setting up the console classpath with ${entries.mkString(", ")}")(
          DebugFilter.All
        )
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
      testEventHandler: TestSuiteEventHandler,
      failIfNoTestFrameworks: Boolean,
      runInParallel: Boolean = false
  ): Task[State] = {
    import state.logger
    implicit val logContext: DebugFilter = DebugFilter.Test

    var failure = false
    val testTasks = projectsToTest.map { project =>
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

      val cwd = project.baseDirectory
      TestTask.runTestSuites(
        state,
        project,
        cwd,
        userTestOptions,
        testFilter,
        failureHandler,
        failIfNoTestFrameworks
      )
    }

    // For now, test execution is only sequential.
    val runAll: List[Task[Int]] => Task[List[Int]] =
      if (runInParallel) {
        Task.gather
      } else {
        Task.sequence
      }

    runAll(testTasks).map { exitCodes =>
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
   * @param skipJargs Skip the interpretation of `-J` options in `args`.
   * @param mode      The run mode.
   */
  def runJVM(
      state: State,
      project: Project,
      javaEnv: JavaEnv,
      cwd: AbsolutePath,
      fqn: String,
      args: Array[String],
      skipJargs: Boolean,
      mode: RunMode
  ): Task[State] = {
    val dag = state.build.getDagFor(project)
    val classpath = project.fullClasspath(dag, state.client)
    val processConfig = JvmProcessForker(javaEnv, classpath, mode)
    val runTask =
      processConfig.runMain(cwd, fqn, args, skipJargs, state.logger, state.commonOptions)
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

    val analysis = {
      state.results.lastSuccessfulResultOrEmpty(project).previous.analysis().toOption match {
        case Some(analysis: Analysis) => analysis
        case _ =>
          logger.warn(
            s"Cannot find main classes in '${project.name}'. No successful compilation detected."
          )
          Analysis.empty
      }
    }

    val mainClasses = analysis.infos.allInfos.values.flatMap(_.getMainClasses).toList
    logger.debug(s"Found ${mainClasses.size} main classes${mainClasses.mkString(": ", ", ", ".")}")(
      DebugFilter.All
    )
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

}
