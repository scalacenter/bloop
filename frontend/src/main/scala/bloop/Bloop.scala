package bloop

import java.nio.file._
import java.util.Optional

import bloop.io.IO
import bloop.io.Timer.timed
import bloop.tasks.CompilationTasks
import sbt.internal.inc.bloop.ZincInternals
import sbt.internal.inc.{ConcreteAnalysisContents, FileAnalysisStore}
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global

object Bloop {

  def main(args: Array[String]): Unit = {
    val base = args.lift(0).getOrElse("..")
    val projects = Project.fromDir(Paths.get(base).resolve(".bloop-config"))
    val componentProvider = ZincInternals.getComponentProvider(IO.getCacheDirectory("components"))
    val compilerCache = new CompilerCache(componentProvider, IO.getCacheDirectory("scala-jars"))
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
        def hasAnalysis(project: Project): Boolean =
          project.previousResult.analysis().isPresent && project.previousResult
            .setup()
            .isPresent

        timed {
          projects.foreach {
            case (name, project) if hasAnalysis(project) =>
              project.origin foreach { origin =>
                val analysisPath =
                  origin.getParent.resolve(s"$name-analysis.bin")
                val analysis = project.previousResult.analysis().get()
                val setup = project.previousResult.setup().get()
                FileAnalysisStore
                  .binary(analysisPath.toFile)
                  .set(ConcreteAnalysisContents(analysis, setup))
              }
            case _ =>
              ()
          }
        }

      case Array("clean") =>
        val newProjects =
          timed {
            val previousResult =
              PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])
            projects.mapValues(_.copy(previousResult = previousResult))
          }
        run(newProjects, compilerCache)

      case Array("seqcompile", projectName) =>
        val newProjects = timed {
          val project = projects(projectName)
          val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
          tasks.sequential(project)
        }
        run(newProjects, compilerCache)

      case Array("naivecompile", projectName) =>
        val newProjects = timed {
          val project = projects(projectName)
          val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
          tasks.parallelNaive(project)
        }
        run(newProjects, compilerCache)

      case Array("compile", projectName) =>
        val newProjects = timed {
          val project = projects(projectName)
          val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
          tasks.parallel(project)
        }
        run(newProjects, compilerCache)

      case _ =>
        println(s"Not understood: '$input'")
        run(projects, compilerCache)
    }
  }

}
