package bloop.engine.tasks

import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

import bloop.bsp.ScalaTestSuites
import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.config.Tag
import bloop.data.Platform
import bloop.data.Project
import bloop.engine.Feedback
import bloop.engine.State
import bloop.engine.tasks.toolchains.ScalaJsToolchain
import bloop.exec.JvmProcessForker
import bloop.io.AbsolutePath
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.task.Task
import bloop.testing.DiscoveredTestFrameworks
import bloop.testing.FingerprintInfo
import bloop.testing.LoggingEventHandler
import bloop.testing.TestInternals
import bloop.util.JavaCompat.EnrichOptional

import monix.execution.atomic.AtomicBoolean
import sbt.internal.inc.Analysis
import sbt.testing.Framework
import sbt.testing.SuiteSelector
import sbt.testing.TaskDef
import sbt.testing.TestSelector
import xsbt.api.Discovery
import xsbti.compile.CompileAnalysis

final case class TestFrameworkWithClasses(
    framework: String,
    classes: List[String]
)

object TestTask {
  implicit private val logContext: DebugFilter = DebugFilter.Test

  /**
   * Does the given project represent a test project? Test project are projects tagged with either
   * `test` or `integration-test`.
   *
   * See [[Tag.Test]], [[Tag.IntegrationTest]]
   *
   * @param project The project to test
   * @return true if `project` is a test project, false otherwise.
   */
  def isTestProject(project: Project): Boolean = {
    project.tags.contains(Tag.Test) ||
    project.tags.contains(Tag.IntegrationTest)
  }

  /**
   * Run discovered test suites for a given project and return a status code.
   *
   * @param state The state with which to test.
   * @param project The project to test.
   * @param cwd The current working directory.
   * @param userTestOptions0 The test options that are passed by the user via CLI. If they
   *                         contain arguments starting with `-J`, they will be interpreted
   *                         as jvm options.
   * @param testFilter The test filter for test suites.
   * @param handler The handler that will intervene if there's an error.
   * @return A status code that will signal success or failure.
   */
  def runTestSuites(
      state: State,
      project: Project,
      cwd: AbsolutePath,
      rawTestOptions: List[String],
      testFilter: String => Boolean,
      testClasses: ScalaTestSuites,
      handler: LoggingEventHandler,
      mode: RunMode
  ): Task[Int] = {
    import state.logger
    val isTest = isTestProject(project)
    def handleEmptyTestFrameworks: Task[Int] = {
      if (isTest) {
        logger.error(s"Missing configured test frameworks in ${project.name}")
        Task.now(1)
      } else {
        logger.warn(s"Missing configured test frameworks in ${project.name}")
        Task.now(0)
      }
    }

    val lastCompilerResult = state.results.latestResult(project)
    val lastSuccessful = state.results.lastSuccessfulResultOrEmpty(project)
    val compileAnalysis = lastSuccessful.previous.analysis().toOption.getOrElse(Analysis.Empty)
    if (compileAnalysis == Analysis.Empty) {
      if (lastCompilerResult == bloop.Compiler.Result.Empty) {
        logger.warn(s"Skipping test for ${project.name} because compiler result is empty")
        Task.now(0)
      } else {
        if (isTest) {
          logger.error(s"Missing compilation to test ${project.name}")
          Task.now(1)
        } else {
          logger.warn(s"Missing compilation to test ${project.name}")
          Task.now(0)
        }
      }
    } else {
      TestTask.discoverTestFrameworks(project, state, mode).flatMap {
        case None => handleEmptyTestFrameworks
        case Some(found) if found.frameworks.isEmpty => handleEmptyTestFrameworks
        case Some(found) =>
          val configuredFrameworks = found.frameworks
          logger.debug(s"Found test frameworks: ${configuredFrameworks.map(_.name).mkString(", ")}")
          val suites =
            discoverTestSuites(
              state,
              project,
              configuredFrameworks,
              compileAnalysis,
              testFilter,
              testClasses
            )
          val discoveredFrameworks = suites.iterator.filterNot(_._2.isEmpty).map(_._1).toList
          val (userJvmOptions, userTestOptions) = rawTestOptions.partition(_.startsWith("-J"))
          val jvmOptions = userJvmOptions.map(_.stripPrefix("-J")) ++ testClasses.jvmOptions
          val envOptions = testClasses.environmentVariables
          val frameworkArgs = considerFrameworkArgs(discoveredFrameworks, userTestOptions, logger)
          val args = project.testOptions.arguments ++ frameworkArgs
          logger.debug(s"Running test suites with arguments: $args")
          logger.debug(s"Running ForkMain with jvm opts: $jvmOptions")
          logger.debug(s"Running ForkMain with env variables: $envOptions")

          found match {
            case DiscoveredTestFrameworks.Jvm(_, forker, loader) =>
              val opts = state.commonOptions
              // FORMAT: OFF
              TestInternals.execute(cwd, forker, loader, suites, args, jvmOptions, envOptions, handler, logger, opts)
              // FORMAT: ON
            case DiscoveredTestFrameworks.Js(_, closeResources) =>
              val cancelled: AtomicBoolean = AtomicBoolean(false)
              def cancel(): Unit = {
                if (!cancelled.getAndSet(true)) {
                  closeResources()
                }
              }

              def reportTestException(t: Throwable): scala.util.Try[Int] = {
                logger.error(Feedback.printException("Unexpected test-related exception", t))
                logger.trace(t)
                scala.util.Success(ExitStatus.TestExecutionError.code)
              }

              val checkCancelled = () => cancelled.get
              TestInternals
                .runJsTestsInProcess(suites, args, handler, checkCancelled, logger)
                .materialize
                .doOnCancel(Task(cancel()))
                .map {
                  case s @ scala.util.Success(_) => closeResources(); s
                  case scala.util.Failure(e) =>
                    e match {
                      case NonFatal(t) =>
                        if (!checkCancelled()) {
                          closeResources()
                          reportTestException(t)
                        } else {
                          t.getCause match {
                            // Swallow the ISE because we know it happens when cancelling
                            case _: IllegalStateException =>
                              logger.debug("Test server has been successfully closed.")
                              scala.util.Success(0)
                            // Swallow the IAE because we know it happens when cancelling
                            case _: IllegalArgumentException =>
                              logger.debug("Test server has been successfully closed.")
                              scala.util.Success(0)
                            case _ => reportTestException(t)
                          }
                        }
                    }
                }
                .dematerialize
          }
      }
    }
  }

  /**
   * Discovers test frameworks in a project for a given state.
   *
   * The discovery will force linking of Scala.js and Native projects,
   * while do nothing for JVM projects (the discovery can operate directly
   * on .class files).
   */
  private[bloop] def discoverTestFrameworks(
      project: Project,
      state: State,
      mode: RunMode = RunMode.Normal
  ): Task[Option[DiscoveredTestFrameworks]] = {
    import state.logger
    implicit val logContext: DebugFilter = DebugFilter.Test
    project.platform match {
      case Platform.Jvm(compileConfig, _, _, runtimeConfig, _, _) =>
        val env = runtimeConfig.getOrElse(compileConfig)
        val dag = state.build.getDagFor(project)
        val classpath = project.fullRuntimeClasspath(dag, state.client)
        val forker = JvmProcessForker(env, classpath, mode)
        val testLoader = forker.newClassLoader(Some(TestInternals.filteredLoader))
        val frameworks = project.testFrameworks.flatMap(f =>
          TestInternals.loadFramework(testLoader, f.names, logger)
        )
        Task.now(Some(DiscoveredTestFrameworks.Jvm(frameworks, forker, testLoader)))

      case Platform.Js(config, toolchain, userMainClass) =>
        val target = ScalaJsToolchain.linkTargetFrom(project, config)
        toolchain match {
          case Some(toolchain) =>
            val dag = state.build.getDagFor(project)
            val fullClasspath = project.fullRuntimeClasspath(dag, state.client).map(_.underlying)

            // Pass in the default scheduler used by this task to the linker
            Task.deferAction { s =>
              toolchain
                .link(config, project, fullClasspath, false, userMainClass, target, s, logger)
                .map {
                  case Success(_) =>
                    logger.info(s"Generated JavaScript file '${target.syntax}'")
                    val fnames = project.testFrameworks.map(_.names)
                    logger.debug(s"Resolving test frameworks: $fnames")
                    val env = state.commonOptions.env.toMap
                    Some(
                      toolchain.discoverTestFrameworks(project, fnames, target, logger, config, env)
                    )

                  case Failure(ex) =>
                    ex.printStackTrace()
                    logger.trace(ex)
                    logger.error(s"JavaScript linking failed with '${ex.getMessage}'")
                    None
                }
            }

          case None =>
            val artifactName = ScalaJsToolchain.artifactNameFrom(config.version)
            val msg = Feedback.missingLinkArtifactFor(project, artifactName, ScalaJsToolchain.name)
            logger.error(msg)
            Task.now(None)
        }

      case _: Platform.Native =>
        logger.warn("Detecting test frameworks in Scala Native projects is not yet supported")
        Task.now(None)
    }
  }

  private[bloop] def considerFrameworkArgs(
      frameworks: List[Framework],
      options: List[String],
      logger: Logger
  ): List[Config.TestArgument] = {
    if (options.isEmpty) Nil
    else {
      val cls = frameworks.map(f => f.getClass.getName)
      frameworks.sortBy(_.name) match {
        case Nil => Nil
        case oneFramework :: Nil =>
          val cls = oneFramework.getClass.getName
          logger.debug(s"Test options '$options' assigned to the only found framework $cls'.")
          List(Config.TestArgument(options, Some(Config.TestFramework(List(cls)))))
        case frameworks =>
          val frameworkNames = frameworks.map(_.name).mkString(", ")
          val (sysProperties, ignoredArgs) = options.partition(s => s.startsWith("-D"))
          if (sysProperties.nonEmpty)
            List(Config.TestArgument(sysProperties, Some(Config.TestFramework(cls))))
          else {
            // Test arguments coming after `--` can only be used if only one mapping is found
            logger.warn(
              s"Ignored CLI test options '$ignoredArgs' can only be applied to one framework, found: $frameworkNames"
            )
            Nil
          }
      }
    }
  }

  case class TaskDefWithFramework(taskDef: TaskDef, framework: Framework)

  private[bloop] def discoverTestSuites(
      state: State,
      project: Project,
      frameworks: List[Framework],
      analysis: CompileAnalysis,
      testFilter: String => Boolean,
      testClasses: ScalaTestSuites
  ): Map[Framework, List[TaskDef]] = {
    import state.logger
    val tests = discoverTests(analysis, frameworks)
    val excluded = project.testOptions.excludes.toSet
    val ungroupedTests = tests.toList.flatMap {
      case (framework, tasks) => tasks.map(taskDef => TaskDefWithFramework(taskDef, framework))
    }
    val (includedTests, excludedTests) = ungroupedTests.partition {
      case TaskDefWithFramework(taskDef, _) =>
        val fqn = taskDef.fullyQualifiedName()
        !excluded(fqn) && testFilter(fqn)
    }
    if (logger.isVerbose) {
      val allNames = ungroupedTests.map(_.taskDef.fullyQualifiedName).mkString(", ")
      val includedNames = includedTests.map(_.taskDef.fullyQualifiedName).mkString(", ")
      val excludedNames = excludedTests.map(_.taskDef.fullyQualifiedName).mkString(", ")
      logger.debug(s"Bloop found the following tests for ${project.name}: $allNames")
      logger.debug(s"The following tests were included by the filter: $includedNames")
      logger.debug(s"The following tests were excluded by the filter: $excludedNames")
    }

    // based on proposal from https://github.com/build-server-protocol/build-server-protocol/issues/249#issuecomment-983435766
    // TaskDef consists of e.g. fullyQualifiedName and selectors
    // selectors is a possibly empty array of selectors which determines suites and tests to run
    // usually it is a Array(new SuiteSelector). However, if only subset of test are supposed to
    // be run, then it can be altered to Array[TestSelector]
    val selectedTests = testClasses.suites.map(entry => (entry.className, entry.tests)).toMap
    includedTests.groupBy(_.framework).mapValues { taskDefs =>
      taskDefs.map {
        case TaskDefWithFramework(taskDef, _) =>
          selectedTests.get(taskDef.fullyQualifiedName()).getOrElse(Nil) match {
            case Nil => taskDef
            case selectedTests =>
              new TaskDef(
                taskDef.fullyQualifiedName(),
                taskDef.fingerprint(),
                false,
                selectedTests.map(test => new TestSelector(test)).toList.toArray
              )
          }
      }
    }
  }

  private[bloop] def discoverTests(
      analysis: CompileAnalysis,
      frameworks: List[Framework]
  ): Map[Framework, List[TaskDef]] = {
    import scala.collection.mutable
    val (subclassPrints, annotatedPrints) = TestInternals.getFingerprints(frameworks)
    val definitions = TestInternals.potentialTests(analysis)
    val discovered =
      Discovery(subclassPrints.map(_.name).toSet, annotatedPrints.map(_.name).toSet)(definitions)
    val tasks = mutable.Map.empty[Framework, mutable.Buffer[TaskDef]]
    val seen = mutable.Set.empty[String]
    frameworks.foreach(tasks(_) = mutable.Buffer.empty)
    discovered.foreach {
      case (defn, discovered) =>
        TestInternals.matchingFingerprints(subclassPrints, annotatedPrints, discovered).foreach {
          case FingerprintInfo(_, _, framework, fingerprint) =>
            if (seen.add(defn.name)) {
              tasks(framework) += new TaskDef(
                defn.name,
                fingerprint,
                false,
                Array(new SuiteSelector)
              )
            }
        }
    }
    tasks.mapValues(_.toList).toMap
  }

  /**
   * Finds the fully qualified names of the test names discovered in a project.
   *
   * @param state   The current state of Bloop.
   * @param project The project for which to find tests.
   * @return An array containing all the testsFQCN that were detected.
   */
  def findTestNamesWithFramework(
      project: Project,
      state: State
  ): Task[List[TestFrameworkWithClasses]] =
    TestTask.discoverTestFrameworks(project, state).map {
      case None => List.empty
      case Some(found) =>
        val frameworks = found.frameworks
        val lastCompileResult = state.results.lastSuccessfulResultOrEmpty(project)
        val analysis = lastCompileResult.previous.analysis().toOption.getOrElse {
          state.logger
            .debug(s"TestsFQCN was triggered, but no compilation detected for ${project.name}")(
              DebugFilter.All
            )
          Analysis.empty
        }
        val tests = discoverTests(analysis, frameworks)
        tests.map {
          case (framework, tasks) =>
            TestFrameworkWithClasses(framework.name, tasks.map(_.fullyQualifiedName))
        }.toList
    }
}
