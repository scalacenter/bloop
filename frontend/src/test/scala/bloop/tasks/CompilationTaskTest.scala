package bloop.tasks

import utest._

import scala.concurrent.ExecutionContext.Implicits.global
import bloop.{Project, ScalaInstance}
import ProjectHelpers._
import bloop.logging.Logger

object CompilationTaskTest extends TestSuite {
  private val logger = new Logger("bloop-test")
  private val compilerCache = CompilationHelpers.compilerCache(logger)

  private val ProjectNameToCompile = "target-project"
  object ArtificialSources {
    val `A.scala` = "package p0\nclass A"
    val `B.scala` = "package p1\nimport p0.A\nclass B extends A"
    val `B2.scala` = "package p1\ntrait B"
    val `C.scala` = "package p2\nimport p0.A\nimport p1.B\nobject C extends A with B"
    val `C2.scala` = "package p2\nimport p0.A\nobject C extends A"
  }

  def checkAfterCleanCompilation(structures: Map[String, Map[String, String]],
                                 dependencies: Map[String, Set[String]],
                                 scalaInstance: ScalaInstance = CompilationHelpers.scalaInstance,
                                 logger: Logger)(
      afterCompile: Map[String, Project] => Unit = (_ => ())) = {
    withProjects(structures, dependencies) { projects =>
      // Check that this is a clean compile!
      assert(projects.forall { case (_, prj) => noPreviousResult(prj) })
      val project = projects(ProjectNameToCompile)
      val tasks = new CompilationTasks(projects, compilerCache, logger)
      val newProjects = tasks.parallelCompile(project)
      afterCompile(newProjects)
    }
  }

  val tests = Tests {
    "compile an empty project" - {
      logger.quietIfSuccess { logger =>
        val projectStructures = Map(ProjectNameToCompile -> Map.empty[String, String])
        val dependencies = Map.empty[String, Set[String]]
        checkAfterCleanCompilation(projectStructures, dependencies, logger = logger) {
          (projects: Map[String, Project]) =>
            val targetProject = projects(ProjectNameToCompile)
            assert(hasPreviousResult(targetProject))
        }
      }
    }

    "Compile with scala 2.12.4" - {
      val scalaInstance = ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.12.4")
      logger.quietIfSuccess { logger =>
        simpleProject(scalaInstance, logger)
      }
    }

    "Compile with Scala 2.12.3" - {
      val scalaInstance = ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.12.3")
      logger.quietIfSuccess { logger =>
        simpleProject(scalaInstance, logger)
      }
    }

    "Compile with scala 2.11.11" - {
      val scalaInstance = ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.11.11")
      logger.quietIfSuccess { logger =>
        simpleProject(scalaInstance, logger)
      }
    }

    "Compile two projects with a dependency" - {
      logger.quietIfSuccess { logger =>
        val projectsStructure = Map(
          "parent" -> Map("A.scala" -> ArtificialSources.`A.scala`),
          ProjectNameToCompile -> Map("B.scala" -> ArtificialSources.`B.scala`)
        )

        val dependencies = Map(ProjectNameToCompile -> Set("parent"))
        checkAfterCleanCompilation(projectsStructure, dependencies, logger = logger) {
          (projects: Map[String, Project]) =>
            assert(projects.forall { case (_, prj) => hasPreviousResult(prj) })
        }
      }
    }

    "Compile one project with two dependencies" - {
      logger.quietIfSuccess { logger =>
        val projectsStructure = Map(
          "parent0" -> Map("A.scala" -> ArtificialSources.`A.scala`),
          "parent1" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
          ProjectNameToCompile -> Map("C.scala" -> ArtificialSources.`C.scala`)
        )

        val dependencies = Map(ProjectNameToCompile -> Set("parent0", "parent1"))
        checkAfterCleanCompilation(projectsStructure, dependencies, logger = logger) {
          (projects: Map[String, Project]) =>
            assert(projects.forall { case (_, prj) => hasPreviousResult(prj) })
        }
      }
    }

    "Un-necessary projects are not compiled" - {
      logger.quietIfSuccess { logger =>
        val projectsStructures = Map(
          "parent" -> Map("A.scala" -> ArtificialSources.`A.scala`),
          "unrelated" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
          ProjectNameToCompile -> Map("C.scala" -> ArtificialSources.`C2.scala`)
        )

        val dependencies = Map(ProjectNameToCompile -> Set("parent"))
        checkAfterCleanCompilation(projectsStructures, dependencies, logger = logger) {
          (projects: Map[String, Project]) =>
            // The unrelated project should not have been compiled
            assert(noPreviousResult(projects("unrelated")))
            assert(hasPreviousResult(projects("parent")))
            assert(hasPreviousResult(projects(ProjectNameToCompile)))
        }
      }
    }

    "There is no result when compilation fails" - {
      logger.quietIfError { logger =>
        val projectsStructure = Map(ProjectNameToCompile -> Map("Error.scala" -> "iwontcompile"))
        checkAfterCleanCompilation(projectsStructure, Map.empty, logger = logger) {
          (projects: Map[String, Project]) =>
            assert(projects.forall { case (_, prj) => noPreviousResult(prj) })
        }
      }
    }
  }

  private def simpleProject(scalaInstance: ScalaInstance, logger: Logger): Unit = {
    val dependencies = Map.empty[String, Set[String]]
    val projectsStructure = Map(ProjectNameToCompile -> Map("A.scala" -> "object A"))
    // Scala bug to report: removing `(_ => ())` fails to compile.
    checkAfterCleanCompilation(projectsStructure, dependencies, scalaInstance, logger)(_ => ())
  }
}
