package bloop

import bloop.io.{AbsolutePath, Paths}
import bloop.io.Timer.timed
import bloop.logging.Logger
import bloop.tasks.{CompilationTasks, TestTasks}
import sbt.internal.inc.bloop.ZincInternals

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global

object Bloop {

  private val logger = new Logger("bloop")

  def main(args: Array[String]): Unit = {
    val baseDirectory = AbsolutePath(args.lift(0).getOrElse(".."))
    val configDirectory = baseDirectory.resolve(".bloop-config")
    val projects = Project.fromDir(configDirectory, logger)
    val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))
    val compilerCache = new CompilerCache(provider, Paths.getCacheDirectory("scala-jars"), logger)
    // TODO: Remove projects and pass in the compilation tasks to abstract over the boilerplate
    run(projects, compilerCache)
  }

  @tailrec
  def run(projects: Map[String, Project], compilerCache: CompilerCache): Unit = {
    val input = scala.io.StdIn.readLine("> ")
    input.split(" ") match {
      case Array("projects") =>
        timed(logger) {
          logger.info(projects.keySet.toList.sorted.mkString(", "))
        }
        run(projects, compilerCache)

      case Array("exit") =>
        val tasks = new CompilationTasks(projects, compilerCache, logger)
        timed(logger) {
          projects.valuesIterator.map { project =>
            tasks.persistAnalysis(project, logger)
          }
        }
        ()

      case Array("clean") =>
        val tasks = new CompilationTasks(projects, compilerCache, logger)
        val newProjects = timed(logger)(tasks.clean(projects.keys.toList))
        run(newProjects, compilerCache)

      case Array("compile", projectName) =>
        val newProjects = timed(logger) {
          val project = projects(projectName)
          val tasks = new CompilationTasks(projects, compilerCache, logger)
          tasks.parallelCompile(project)
        }
        run(newProjects, compilerCache)

      case Array("test", projectName) =>
        val tasks = new TestTasks(projects, logger)
        val testLoader = tasks.getTestLoader(projectName)
        val tests = tasks.definedTests(projectName, testLoader)
        tests.foreach {
          case (runner, taskDefs) =>
            tasks.runTests(runner(), taskDefs.toArray)
        }

      case _ =>
        logger.error(s"Not understood: '$input'")
        run(projects, compilerCache)
    }
  }

}
