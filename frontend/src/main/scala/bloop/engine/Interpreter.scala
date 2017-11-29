package bloop.engine

import bloop.cli.{CliOptions, Commands, CommonOptions, ExitStatus}
import bloop.io.{AbsolutePath, Paths}
import bloop.io.Timer.timed
import bloop.logging.Logger
import bloop.reporter.ReporterConfig
import bloop.tasks.{CompilationTasks, TestTasks}
import ExecutionContext.threadPool
import bloop.util.TopologicalSort
import bloop.{CompilerCache, Project}
import sbt.internal.inc.bloop.ZincInternals

object Interpreter {
  def execute(action: Action, logger: Logger): ExitStatus = action match {
    case Exit(exitStatus) => exitStatus
    case Print(msg, commonOptions, next) =>
      printOut(msg, commonOptions)
      execute(next, logger)
    case Run(Commands.About(cliOptions), next) =>
      logger.verboseIf(cliOptions.verbose) {
        printAbout(cliOptions)
      }
      execute(next, logger)
    case Run(Commands.Clean(projects, cliOptions), next) =>
      logger.verboseIf(cliOptions.verbose) {
        clean(projects, cliOptions, logger)
      }
      execute(next, logger)
    case Run(Commands.Compile(projectName, incremental, scalacstyle, cliOptions), next) =>
      logger.verboseIf(cliOptions.verbose) {
        val reporterConfig =
          if (scalacstyle) ReporterConfig.scalacFormat else ReporterConfig.defaultFormat
        compile(projectName, incremental, cliOptions, reporterConfig, logger)
      }
      execute(next, logger)
    case Run(Commands.Test(projectName, aggregate, scalacstyle, cliOptions), next) =>
      logger.verboseIf(cliOptions.verbose) {
        val reporterConfig =
          if (scalacstyle) ReporterConfig.scalacFormat else ReporterConfig.defaultFormat
        test(projectName, aggregate, cliOptions, reporterConfig, logger)
      }
      execute(next, logger)
  }

  private final val t = "    "
  private def printAbout(cliOptions: CliOptions): ExitStatus = {
    val bloopName = bloop.internal.build.BuildInfo.name
    val bloopVersion = bloop.internal.build.BuildInfo.version
    val scalaVersion = bloop.internal.build.BuildInfo.scalaVersion
    val zincVersion = bloop.internal.build.BuildInfo.zincVersion
    val developers = bloop.internal.build.BuildInfo.developers.mkString(", ")
    val header =
      s"""|$t   _____            __         ______           __
          |$t  / ___/_________ _/ /___ _   / ____/__  ____  / /____  _____
          |$t  \\__ \\/ ___/ __ `/ / __ `/  / /   / _ \\/ __ \\/ __/ _ \\/ ___/
          |$t ___/ / /__/ /_/ / / /_/ /  / /___/ /__/ / / / /_/ /__/ /
          |$t/____/\\___/\\__,_/_/\\__,_/   \\____/\\___/_/ /_/\\__/\\___/_/
          |""".stripMargin
    val versions = s"""
                      |$t${bloopName.capitalize} version    `$bloopVersion`
                      |${t}Zinc version     `$zincVersion`
                      |${t}Scala version    `$scalaVersion`""".stripMargin
    cliOptions.common.out.println(header)
    cliOptions.common.out.println(t) // This is the only way to add newline, otherwise ignored
    cliOptions.common.out.println(s"$t$bloopName is made with love at the Scala Center <3")
    cliOptions.common.out.println(t)
    cliOptions.common.out.println(versions)
    cliOptions.common.out.println(t)
    cliOptions.common.out.println(s"${t}It is maintained by $developers.")

    ExitStatus.Ok
  }

  private def printOut(msg: String, commonOptions: CommonOptions): ExitStatus = {
    commonOptions.out.println(msg)
    ExitStatus.Ok
  }

  private def compilationTasks(projects: Map[String, Project], logger: Logger): CompilationTasks = {
    val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))
    val compilerCache = new CompilerCache(provider, Paths.getCacheDirectory("scala-jars"), logger)
    CompilationTasks(projects, compilerCache, logger)
  }

  private def getConfigDir(cliOptions: CliOptions): AbsolutePath = {
    cliOptions.configDir
      .map(AbsolutePath.apply)
      .getOrElse(cliOptions.common.workingPath.resolve(".bloop-config"))
  }

  private def compile(projectName: String,
                      incremental: Boolean,
                      cliOptions: CliOptions,
                      reporterConfig: ReporterConfig,
                      logger: Logger): ExitStatus = timed(logger) {
    val configDir = getConfigDir(cliOptions)
    val projects = Project.fromDir(configDir, logger)
    val tasks = compilationTasks(projects, logger)
    val project = projects(projectName)
    if (incremental) {
      val newProjects = tasks.parallelCompile(project, reporterConfig)
      Project.update(configDir, newProjects)
      ExitStatus.Ok
    } else {
      val newProjects = tasks.clean(projects.keys.toList)
      val newTasks = tasks.copy(initialProjects = newProjects)
      newTasks.parallelCompile(project, reporterConfig)
      ExitStatus.Ok
    }
  }

  private def test(projectName: String,
                   aggregate: Boolean,
                   cliOptions: CliOptions,
                   reporterConfig: ReporterConfig,
                   logger: Logger): ExitStatus = timed(logger) {
    val configDir = getConfigDir(cliOptions)
    val projects = Project.fromDir(configDir, logger)
    val compilation = compilationTasks(projects, logger)
    val compiledProjects = compilation.parallelCompile(projects(projectName), reporterConfig)
    Project.update(configDir, compiledProjects)
    val testTasks = new TestTasks(compiledProjects, logger)
    val projectsToTest =
      if (aggregate) TopologicalSort.reachable(projects(projectName), projects).keys
      else List(projectName)

    def test(projectName: String): Unit = {
      val testLoader = testTasks.getTestLoader(projectName)
      val tests = testTasks.definedTests(projectName, testLoader)
      tests.foreach {
        case (lazyRunner, taskDefs) =>
          val runner = lazyRunner()
          testTasks.runTests(runner, taskDefs.toArray)
          runner.done()
      }
    }

    projectsToTest.foreach(test)
    ExitStatus.Ok
  }

  private def clean(projectNames: List[String],
                    cliOptions: CliOptions,
                    logger: Logger): ExitStatus = {
    val configDir = getConfigDir(cliOptions)
    val projects = Project.fromDir(configDir, logger)
    val tasks = compilationTasks(projects, logger)
    val cleanProjects = tasks.clean(projectNames)
    Project.update(configDir, cleanProjects)
    ExitStatus.Ok
  }
}
