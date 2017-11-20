package bloop

import bloop.io.{AbsolutePath, Paths}
import bloop.io.Timer.timed
import bloop.tasks.CompilationTasks
import sbt.internal.inc.bloop.ZincInternals

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global

object Bloop {

  def main(args: Array[String]): Unit = {
    val baseDirectory = AbsolutePath(args.lift(0).getOrElse(".."))
    val configDirectory = baseDirectory.resolve(".bloop-config")
    val projects = Project.fromDir(configDirectory)
    val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))
    val compilerCache = new CompilerCache(provider, Paths.getCacheDirectory("scala-jars"))
    // TODO: Remove projects and pass in the compilation tasks to abstract over the boilerplate
    run(projects, compilerCache)
  }

  @tailrec
  def run(projects: Map[String, Project], compilerCache: CompilerCache): Unit = {
    val input = scala.io.StdIn.readLine("> ")
    input.split(" ") match {
      case Array("projects") =>
        timed {
          println(projects.keySet.toList.sorted.mkString(", "))
        }
        run(projects, compilerCache)

      case Array("exit") =>
        val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
        timed {
          projects.valuesIterator.map { project =>
            tasks.persistAnalysis(project, QuietLogger)
          }
        }
        ()

      case Array("clean") =>
        val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
        val newProjects = timed(tasks.clean(projects.keys.toList))
        run(newProjects, compilerCache)

      case Array("compile", projectName) =>
        val newProjects = timed {
          val project = projects(projectName)
          val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
          tasks.parallelCompile(project)
        }
        run(newProjects, compilerCache)

      case _ =>
        println(s"Not understood: '$input'")
        run(projects, compilerCache)
    }
  }

}
