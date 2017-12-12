package bloop.tasks

import utest._
import bloop.{ScalaInstance}
import bloop.engine.State
import bloop.tasks.ProjectHelpers.{ checkAfterCleanCompilation,
  getProject,
  hasPreviousResult,
  noPreviousResult,
  RootProject
}

object CompilationTaskTest extends TestSuite {
  object ArtificialSources {
    val `A.scala` = "package p0\nclass A"
    val `B.scala` = "package p1\nimport p0.A\nclass B extends A"
    val `B2.scala` = "package p1\ntrait B"
    val `C.scala` = "package p2\nimport p0.A\nimport p1.B\nobject C extends A with B"
    val `C2.scala` = "package p2\nimport p0.A\nobject C extends A"
  }

  val tests = Tests {
    "compile an empty project" - {
      val projectStructures = Map(RootProject -> Map.empty[String, String])
      val dependencies = Map.empty[String, Set[String]]
      checkAfterCleanCompilation(projectStructures, dependencies, quiet = true) { (state: State) =>
        val targetProject = getProject(RootProject, state)
        assert(hasPreviousResult(targetProject, state))
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
      val projectsStructure = Map(
        "parent" -> Map("A.scala" -> ArtificialSources.`A.scala`),
        RootProject -> Map("B.scala" -> ArtificialSources.`B.scala`)
      )

      val dependencies = Map(RootProject -> Set("parent"))
      checkAfterCleanCompilation(projectsStructure, dependencies, quiet = true) { (state: State) =>
        assert(state.build.projects.forall(p => hasPreviousResult(p, state)))
      }
    }

    "Compile one project with two dependencies" - {
      val projectsStructure = Map(
        "parent0" -> Map("A.scala" -> ArtificialSources.`A.scala`),
        "parent1" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
        RootProject -> Map("C.scala" -> ArtificialSources.`C.scala`)
      )

      val dependencies = Map(RootProject -> Set("parent0", "parent1"))
      checkAfterCleanCompilation(projectsStructure, dependencies, quiet = true) { (state: State) =>
        assert(state.build.projects.forall(p => hasPreviousResult(p, state)))
      }
    }

    "Un-necessary projects are not compiled" - {
      val projectsStructures = Map(
        "parent" -> Map("A.scala" -> ArtificialSources.`A.scala`),
        "unrelated" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
        RootProject -> Map("C.scala" -> ArtificialSources.`C2.scala`)
      )

      val dependencies = Map(RootProject -> Set("parent"))
      checkAfterCleanCompilation(projectsStructures, dependencies, quiet = true) { (state: State) =>
        // The unrelated project should not have been compiled
        assert(noPreviousResult(getProject("unrelated", state), state))
        assert(hasPreviousResult(getProject("parent", state), state))
        assert(hasPreviousResult(getProject(RootProject, state), state))
      }
    }

    "There is no result when compilation fails" - {
      val projectsStructure = Map(RootProject -> Map("Error.scala" -> "iwontcompile"))
      checkAfterCleanCompilation(projectsStructure, Map.empty, failure = true) { (state: State) =>
        assert(state.build.projects.forall(p => noPreviousResult(p, state)))
      }
    }
  }

  private def simpleProject(scalaInstance: ScalaInstance): Unit = {
    val dependencies = Map.empty[String, Set[String]]
    val structures = Map(RootProject -> Map("A.scala" -> "object A"))
    // Scala bug to report: removing `(_ => ())` fails to compile.
    checkAfterCleanCompilation(structures, dependencies, scalaInstance, quiet = true)(_ => ())
  }
}
