package blossom

import java.nio.file._
import java.util.{Map, Optional}

import xsbti.compile.{CompileAnalysis, CompileResult, MiniSetup, PreviousResult}

import scala.util.Random

object Blossom {

  def plan(base: String, projects: Map[String, Project]): Array[Project] = {
    val plan = new Array[Project](projects.size)
    var idx  = 0

    def push(project: Project): Unit = {
      project.dependencies.foreach(p => push(projects.get(p)))
      if (plan.contains(project)) ()
      else {
        plan(idx) = project
        idx += 1
      }
    }

    Option(projects.get(base)).foreach(push)
    plan
  }

  def main(args: Array[String]): Unit = {
    val base = args.lift(0).getOrElse("..")

    val projects = Project.fromDir(Paths.get(base).resolve(".blossom-config"))
    println("Known projects: " + projects.keySet())

    val blossomHome = Paths.get(sys.props("user.home")).resolve(".")
    val componentProvider = new ComponentProvider(
      blossomHome.resolve("components"))
    val scalaJarsTarget = blossomHome.resolve("scala-jars")
    val compilerCache   = new CompilerCache(componentProvider, scalaJarsTarget)

    var input = ""
    while ({ input = scala.io.StdIn.readLine("> "); input } != "exit") {
      input.split(" ") match {
        case Array("compile", projectName) =>
          compile(projectName, projects, compilerCache)
        case Array("clean") =>
          val previousResult = PreviousResult.of(
            Optional.empty[CompileAnalysis],
            Optional.empty[MiniSetup])
          projects.forEach {
            case (name, project) =>
              projects.put(name, project.copy(previousResult = previousResult))
          }
      }
      compile(input, projects, compilerCache)
    }

  }

  def compile(name: String,
              projects: Map[String, Project],
              compilerCache: CompilerCache): Unit = {
    val start = System.nanoTime()
    val tasks = plan(name, projects)
    println(
      "Compilation plan: " + tasks
        .filterNot(_ == null)
        .map(_.name)
        .mkString(", "))

    tasks.foreach {
      case null => ()
      case project =>
        val newResult = compile(project, compilerCache)
        val result = PreviousResult.of(Optional.of(newResult.analysis()),
                                       Optional.of(newResult.setup()))
        val newProject = project.copy(previousResult = result)
        projects.put(project.name, project)
    }

    val end   = System.nanoTime()
    val taken = end - start
    println("Took: " + (taken.toDouble / 1e6) + "ms")
  }

  def compile(project: Project, compilerCache: CompilerCache): CompileResult = {
    val scalaOrganization = project.scalaInstance.organization
    val scalaName         = project.scalaInstance.name
    val scalaVersion      = project.scalaInstance.version
    val compiler          = compilerCache.get(scalaOrganization, scalaName, scalaVersion)
    println("About to compile " + project.name)
    Compiler(project, compiler)
  }

  def changeRandom(projects: Map[String, Project]): Unit = {
    val nb  = Random.nextInt(projects.size())
    val pjs = projects.values().iterator()
    for (_ <- 1 to nb) pjs.next()
    val toChange = pjs.next()
    val srcs     = IO.getAll(toChange.sourceDirectories.head, "glob:**.scala")
    val nb2      = Random.nextInt(srcs.length)
    val src      = srcs(nb2)
    Files.write(src,
                java.util.Arrays.asList("// foobar\n"),
                StandardOpenOption.APPEND)
  }

}
