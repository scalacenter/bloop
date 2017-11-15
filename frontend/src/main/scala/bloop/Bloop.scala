package bloop

import java.nio.file._
import java.util.Optional

import bloop.tasks.CompilationTask
import bloop.util.TopologicalSort
import sbt.internal.inc.{ConcreteAnalysisContents, FileAnalysisStore}
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

object Bloop {

  def plan(base: String, projects: Map[String, Project]): Array[Project] = {
    val plan = new Array[Project](projects.size)
    var idx  = 0

    def push(project: Project): Unit = {
      project.dependencies.foreach(p => push(projects(p)))
      if (plan.contains(project)) ()
      else {
        plan(idx) = project
        idx += 1
      }
    }

    Option(projects(base)).foreach(push)
    plan
  }

  def main(args: Array[String]): Unit = {
    val base = args.lift(0).getOrElse("..")

    val projects          = Project.fromDir(Paths.get(base).resolve(".bloop-config"))
    val bloopHome         = Paths.get(sys.props("user.home")).resolve(".bloop")
    val componentProvider = new ComponentProvider(bloopHome.resolve("components"))
    val scalaJarsTarget   = bloopHome.resolve("scala-jars")
    val compilerCache     = new CompilerCache(componentProvider, scalaJarsTarget)

    run(projects, compilerCache)
  }

  @inline def timed[T](op: => T): T = {
    val start   = System.nanoTime()
    val result  = op
    val elapsed = (System.nanoTime() - start).toDouble / 1e6
    println(s"Elapsed: $elapsed ms")
    result
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
                val setup    = project.previousResult.setup().get()
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
          val tasks   = TopologicalSort.tasks(project, projects).flatten
          val changedProjects =
            tasks.map { project =>
              val inputs = CompilationTask.toCompileInputs(project, compilerCache, QuietLogger)
              val result = Compiler.compile(inputs)
              val previousResult =
                PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
              project.name -> project.copy(previousResult = previousResult)
            }.toMap
          projects ++ changedProjects
        }
        run(newProjects, compilerCache)

      case Array("naivecompile", projectName) =>
        val newProjects = timed {
          val project = projects(projectName)
          val steps   = TopologicalSort.tasks(project, projects)
          val changedProjects =
            steps.flatMap { tasks =>
              tasks.par.map { project =>
                val inputs = CompilationTask.toCompileInputs(project, compilerCache, QuietLogger)
                val result = Compiler.compile(inputs)
                val previousResult =
                  PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
                project.name -> project.copy(previousResult = previousResult)
              }
            }.toMap
          projects ++ changedProjects
        }
        run(newProjects, compilerCache)

      case Array("compile", projectName) =>
        val newProjects = timed {
          CompilationTask(projects(projectName), projects, compilerCache)
        }
        run(newProjects, compilerCache)

      case _ =>
        println(s"Not understood: '$input'")
        run(projects, compilerCache)
    }
  }

  def changeRandom(projects: Map[String, Project]): Unit = {
    val nb  = Random.nextInt(projects.size)
    val pjs = projects.values.iterator
    for (_ <- 1 to nb) pjs.next()
    val toChange = pjs.next()
    val srcs     = IO.getAll(toChange.sourceDirectories.head, "glob:**.scala")
    val nb2      = Random.nextInt(srcs.length)
    val src      = srcs(nb2)
    Files.write(src, java.util.Arrays.asList("// foobar\n"), StandardOpenOption.APPEND)
    ()
  }

}
