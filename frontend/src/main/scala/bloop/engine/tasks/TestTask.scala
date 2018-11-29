package bloop.engine.tasks

import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.data.{Platform, Project}
import bloop.engine.{Dag, Feedback, State}
import bloop.engine.tasks.toolchains.ScalaJsToolchain
import bloop.exec.Forker
import bloop.io.AbsolutePath
import bloop.logging.{DebugFilter, Logger}
import bloop.testing.{DiscoveredTestFrameworks, LoggingEventHandler, TestInternals}
import bloop.util.JavaCompat.EnrichOptional
import monix.eval.Task
import monix.execution.atomic.AtomicBoolean
import sbt.internal.inc.Analysis
import sbt.testing.{Framework, SuiteSelector, TaskDef}
import xsbt.api.Discovery
import xsbti.compile.CompileAnalysis

import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object TestTask {
  implicit private val logContext: DebugFilter = DebugFilter.Test

  /**
   * Run discovered test suites for a given project and return a status code.
   *
   * @param state The state with which to test.
   * @param project The project to test.
   * @param cwd The current working directory.
   * @param userTestOptions The test options that are passed by the user via CLI.
   * @param testFilter The test filter for test suites.
   * @param failureHandler The handler that will intervene if there's an error.
   * @return A status code that will signal success or failure.
   */
  def runTestSuites(
      state: State,
      project: Project,
      cwd: AbsolutePath,
      userTestOptions: List[String],
      testFilter: String => Boolean,
      failureHandler: LoggingEventHandler
  ): Task[Int] = {
    import state.logger
    TestTask.discoverTestFrameworks(project, state).flatMap {
      case None => Task.now(ExitStatus.TestExecutionError.code)
      case Some(found) =>
        val frameworks = found.frameworks
        if (frameworks.isEmpty) logger.error("No test frameworks found")
        else logger.debug(s"Found test frameworks: ${frameworks.map(_.name).mkString(", ")}")

        val frameworkArgs = considerFrameworkArgs(frameworks, userTestOptions, logger)
        val args = fixTestOptions(project, project.testOptions.arguments ++ frameworkArgs)
        logger.debug(s"Running test suites with arguments: $args")

        val lastCompileResult = state.results.lastSuccessfulResultOrEmpty(project)
        val analysis = lastCompileResult.analysis().toOption.getOrElse {
          logger.warn(
            s"Test execution was triggered, but no compilation detected for ${project.name}")
          Analysis.empty
        }

        val discovered = discoverTestSuites(state, project, frameworks, analysis, testFilter)
        found match {
          case DiscoveredTestFrameworks.Jvm(_, forker, loader) =>
            val opts = state.commonOptions
            TestInternals
              .execute(cwd, forker, loader, discovered, args, failureHandler, logger, opts)
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
              .runJsTestsInProcess(discovered, args, failureHandler, checkCancelled, logger)
              .materialize
              .doOnCancel(Task(cancel()))
              .map {
                case s @ scala.util.Success(exitCode) => closeResources(); s
                case f @ scala.util.Failure(e) =>
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

  /**
   * Discovers test frameworks in a project for a given state.
   *
   * The discovery will force linking of Scala.js and Native projects,
   * while do nothing for JVM projects (the discovery can operate directly
   * on .class files).
   */
  private[bloop] def discoverTestFrameworks(
      project: Project,
      state: State
  ): Task[Option[DiscoveredTestFrameworks]] = {
    import state.logger
    implicit val logContext: DebugFilter = DebugFilter.Test
    project.platform match {
      case Platform.Jvm(env, _, _) =>
        val classpath = project.fullClasspathFor(state.build.getDagFor(project))
        val forker = Forker(env, classpath)
        val testLoader = forker.newClassLoader(Some(TestInternals.filteredLoader))
        val frameworks = project.testFrameworks.flatMap(f =>
          TestInternals.loadFramework(testLoader, f.names, logger))
        Task.now(Some(DiscoveredTestFrameworks.Jvm(frameworks, forker, testLoader)))

      case Platform.Js(config, toolchain, userMainClass) =>
        val target = ScalaJsToolchain.linkTargetFrom(project, config)
        toolchain match {
          case Some(toolchain) =>
            toolchain.link(config, project, false, userMainClass, target, state.logger).map {
              case Success(_) =>
                logger.info(s"Generated JavaScript file '${target.syntax}'")
                val fnames = project.testFrameworks.map(_.names)
                logger.debug(s"Resolving test frameworks: $fnames")
                val baseDir = project.baseDirectory
                val env = state.commonOptions.env.toMap
                Some(toolchain.discoverTestFrameworks(project, fnames, target, logger, config, env))

              case Failure(ex) =>
                ex.printStackTrace()
                logger.trace(ex)
                logger.error(s"JavaScript linking failed with '${ex.getMessage}'")
                None
            }

          case None =>
            val artifactName = ScalaJsToolchain.artifactNameFrom(config.version)
            val msg = Feedback.missingLinkArtifactFor(project, artifactName, ScalaJsToolchain.name)
            logger.error(msg)
            Task.now(None)
        }

      case _: Platform.Native =>
        logger.error("Detecting test frameworks in Scala Native projects it not yet supported")
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
      frameworks match {
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

  private[bloop] def discoverTestSuites(
      state: State,
      project: Project,
      frameworks: List[Framework],
      analysis: CompileAnalysis,
      testFilter: String => Boolean
  ): Map[Framework, List[TaskDef]] = {
    import state.logger
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
      logger.debug(s"Bloop found the following tests for ${project.name}: $allNames")
      logger.debug(s"The following tests were included by the filter: $includedNames")
      logger.debug(s"The following tests were excluded by the filter: $excludedNames")
    }
    includedTests.groupBy(_._1).mapValues(_.map(_._2))
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

  /**
   * Fixes the test arguments for a given framework.
   *
   * This is a generic function that accumulates fixes we do to the test arguments
   * that a build has exported. https://github.com/scalacenter/bloop/issues/658 is
   * a good example of a test option (`-h` in Scalatest) which requires us to check
   * that its path exists.
   *
   * @param project The project we fix test options for.
   * @param args The test arguments.
   * @return The list of fixed test arguments.
   */
  def fixTestOptions(
      project: Project,
      args: List[Config.TestArgument]
  ): List[Config.TestArgument] = {
    import java.nio.file.{Files, Paths}
    args.map {
      case Config.TestArgument(testArg :: testArgs, f @ Some(Config.TestFramework.ScalaTest)) =>
        val fixedArgs = testArgs.foldLeft(List(testArg)) {
          case (Nil, current) => current :: Nil
          case (rest @ previous :: _, current) =>
            if (previous != "-h") current :: rest
            else {
              val currentPath = Paths.get(current)
              val path = {
                if (currentPath.isAbsolute) currentPath
                else {
                  val potentialPath = project.baseDirectory.resolve(current)
                  if (potentialPath.exists) potentialPath.underlying
                  else {
                    if (potentialPath.getParent.exists)
                      Files.createFile(potentialPath.underlying)
                    else {
                      Files.createDirectories(potentialPath.getParent.underlying)
                      Files.createFile(potentialPath.underlying)
                    }
                  }
                }
              }

              path.toAbsolutePath.toString :: rest
            }
        }

        Config.TestArgument(fixedArgs, f)
      case a => a
    }
  }

}
