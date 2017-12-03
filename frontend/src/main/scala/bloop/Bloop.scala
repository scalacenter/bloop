package bloop

import bloop.engine.ExecutionContext
import bloop.io.{AbsolutePath, Paths}
import bloop.io.Timer.timed
import bloop.logging.Logger
import bloop.reporter.ReporterConfig
import bloop.tasks.{CompilationTasks, TestTasks}
import sbt.internal.inc.bloop.ZincInternals

import scala.annotation.tailrec

object Bloop {

  private val logger = Logger.get

  def main(args: Array[String]): Unit = {
    val baseDirectory = AbsolutePath(args.lift(0).getOrElse(".."))
    val configDirectory = baseDirectory.resolve(".bloop-config")
    val projects = Project.fromDir(configDirectory, logger)
    val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))
    val compilerCache = new CompilerCache(provider, Paths.getCacheDirectory("scala-jars"), logger)
    // TODO: Remove projects and pass in the compilation tasks to abstract over the boilerplate
    ExecutionContext.withFixedThreadPool { run(projects, compilerCache)(_) }
  }

  @tailrec
  def run(projects: Map[String, Project], compilerCache: CompilerCache)(
      implicit executionContext: ExecutionContext): Unit = {
    val input = scala.io.StdIn.readLine("> ")
    input.split(" ") match {
      case Array("projects") =>
        timed(logger) {
          logger.info(projects.keySet.toList.sorted.mkString(", "))
        }
        run(projects, compilerCache)

      case Array("exit") =>
        timed(logger) {
          projects.valuesIterator.foreach(CompilationTasks.persistAnalysis(_, logger))
        }

      case Array("clean") =>
        val tasks = new CompilationTasks(projects, compilerCache, logger)
        val newProjects = timed(logger)(tasks.clean(projects.keys.toList))
        run(newProjects, compilerCache)

      case Array("compile", projectName) =>
        val newProjects = timed(logger) {
          val project = projects(projectName)
          val tasks = new CompilationTasks(projects, compilerCache, logger)
          tasks.parallelCompile(project, ReporterConfig.defaultFormat)
        }
        run(newProjects, compilerCache)

      case Array("test", projectName) =>
        val tasks = new TestTasks(projects, logger)
        val testLoader = tasks.getTestLoader(projectName)
        val tests = tasks.definedTests(projectName, testLoader)
        tests.foreach {
          case (lazyRunner, taskDefs) =>
            val runner = lazyRunner()
            tasks.runTests(runner, taskDefs.toArray)
            runner.done()
        }

      case _ =>
        logger.error(s"Not understood: '$input'")
        run(projects, compilerCache)
    }
  }

}
