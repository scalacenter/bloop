package bloop.tasks

import utest._

import scala.concurrent.ExecutionContext.Implicits.global

import bloop.{Project, QuietLogger, ScalaInstance}

import CompilationHelpers._
import ProjectHelpers._

object CompilationTaskTest extends TestSuite {
  val tests = Tests {
    "compile an empty project" - {
      val projectStructures =
        Map("empty" -> Map.empty[String, String])

      val dependencies = Map.empty[String, Set[String]]

      withProjects(projectStructures, dependencies) { projects =>
        val project = projects("empty")
        assert(noPreviousResult(project))

        val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
        val newProjects = tasks.parallelCompile(project)
        val newProject = newProjects("empty")

        assert(hasPreviousResult(newProject))
      }
    }

    "Compile with scala 2.12.4" - {
      val scalaInstance = ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.12.4")
      simpleProject(scalaInstance)
    }

    "Compile with Scala 2.12.3" - {
      val scalaInstance = ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.12.3")
      simpleProject(scalaInstance)
    }

    "Compile with scala 2.11.11" - {
      val scalaInstance = ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.11.11")
      simpleProject(scalaInstance)
    }

    "Compile two projects with a dependency" - {
      val projectStructures =
        Map(
          "parent" -> Map("A.scala" -> """package p0
                                         |class A""".stripMargin),
          "child" -> Map("B.scala" -> """package p1
                                        |import p0.A
                                        |class B extends A""".stripMargin)
        )

      val dependencies = Map("child" -> Set("parent"))

      withProjects(projectStructures, dependencies) { projects =>
        assert(projects.forall { case (_, prj) => noPreviousResult(prj) })

        val project = projects("child")
        val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
        val newProjects = tasks.parallelCompile(project)

        assert(newProjects.forall { case (_, prj) => hasPreviousResult(prj) })
      }

    }

    "Compile one project with two dependencies" - {
      val projectStructures =
        Map(
          "parent0" -> Map("A.scala" -> """package p0
                                          |trait A""".stripMargin),
          "parent1" -> Map("B.scala" -> """package p1
                                          |trait B""".stripMargin),
          "child" -> Map("C.scala" -> """package p2
                                        |import p0.A
                                        |import p1.B
                                        |object C extends A with B""".stripMargin)
        )

      val dependencies = Map("child" -> Set("parent0", "parent1"))

      withProjects(projectStructures, dependencies) { projects =>
        assert(projects.forall { case (_, prj) => noPreviousResult(prj) })

        val child = projects("child")
        val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
        val newProjects = tasks.parallelCompile(child)

        assert(newProjects.forall { case (_, prj) => hasPreviousResult(prj) })
      }
    }

    "Un-necessary projects are not compiled" - {
      val projectStructures =
        Map(
          "parent" -> Map("A.scala" -> """package p0
                                         |trait A""".stripMargin),
          "unrelated" -> Map("B.scala" -> """package p1
                                            |trait B""".stripMargin),
          "child" -> Map("C.scala" -> """package p2
                                        |import p0.A
                                        |object C extends A""".stripMargin)
        )

      val dependencies = Map("child" -> Set("parent"))

      withProjects(projectStructures, dependencies) { projects =>
        assert(projects.forall { case (_, prj) => noPreviousResult(prj) })

        val child = projects("child")
        val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
        val newProjects = tasks.parallelCompile(child)

        // The unrelated project should not have been compiled
        assert(noPreviousResult(newProjects("unrelated")))
        assert(hasPreviousResult(newProjects("parent")))
        assert(hasPreviousResult(newProjects("child")))
      }
    }
  }

  private def simpleProject(scalaInstance: ScalaInstance): Unit = {
    val projectStructures =
      Map("prj" -> Map("A.scala" -> "object A"))

    val dependencies = Map.empty[String, Set[String]]

    val scalaInstance = ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.11.11")
    withProjects(projectStructures, dependencies, scalaInstance) { projects =>
      val project = projects("prj")

      assert(noPreviousResult(project))

      val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
      val newProjects = tasks.parallelCompile(project)
      val newProject = newProjects("prj")

      assert(hasPreviousResult(newProject))
    }
  }

  private def hasPreviousResult(project: Project): Boolean = {
    project.previousResult.analysis.isPresent &&
    project.previousResult.setup.isPresent
  }

  private def noPreviousResult(project: Project): Boolean = !hasPreviousResult(project)
}
