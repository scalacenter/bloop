package bloop.tasks

import utest._
import bloop.{Project, ScalaInstance}
import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Exit, Interpreter, Run, State}
import bloop.logging.Logger

object CompilationTaskTest extends TestSuite {
  private val logger = Logger.get

  private val ProjectToCompile = "target-project"
  object ArtificialSources {
    val `A.scala` = "package p0\nclass A"
    val `B.scala` = "package p1\nimport p0.A\nclass B extends A"
    val `B2.scala` = "package p1\ntrait B"
    val `C.scala` = "package p2\nimport p0.A\nimport p1.B\nobject C extends A with B"
    val `C2.scala` = "package p2\nimport p0.A\nobject C extends A"
  }

  def getProject(name: String, state: State): Project =
    state.build.getProjectFor(name).getOrElse(sys.error(s"Project '$name' does not exist!"))

  import ProjectHelpers.{withState, noPreviousResult, hasPreviousResult}
  def checkAfterCleanCompilation(structures: Map[String, Map[String, String]],
                                 dependencies: Map[String, Set[String]],
                                 scalaInstance: ScalaInstance = CompilationHelpers.scalaInstance,
                                 logger: Logger)(afterCompile: State => Unit = (_ => ())) = {
    withState(structures, dependencies, scalaInstance = scalaInstance) { (state: State) =>
      // Check that this is a clean compile!
      val projects = state.build.projects
      assert(projects.forall(p => noPreviousResult(p, state)))
      val project = getProject(ProjectToCompile, state)
      val action = Run(Commands.Compile(ProjectToCompile, incremental = true), Exit(ExitStatus.Ok))
      val compiledState = Interpreter.execute(action, state)
      afterCompile(compiledState)
    }
  }

  val tests = Tests {
    "compile an empty project" - {
      logger.quietIfSuccess { logger =>
        val projectStructures = Map(ProjectToCompile -> Map.empty[String, String])
        val dependencies = Map.empty[String, Set[String]]
        checkAfterCleanCompilation(projectStructures, dependencies, logger = logger) {
          (state: State) =>
            val targetProject = getProject(ProjectToCompile, state)
            assert(hasPreviousResult(targetProject, state))
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
          ProjectToCompile -> Map("B.scala" -> ArtificialSources.`B.scala`)
        )

        val dependencies = Map(ProjectToCompile -> Set("parent"))
        checkAfterCleanCompilation(projectsStructure, dependencies, logger = logger) {
          (state: State) =>
            assert(state.build.projects.forall(p => hasPreviousResult(p, state)))
        }
      }
    }

    "Compile one project with two dependencies" - {
      logger.quietIfSuccess { logger =>
        val projectsStructure = Map(
          "parent0" -> Map("A.scala" -> ArtificialSources.`A.scala`),
          "parent1" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
          ProjectToCompile -> Map("C.scala" -> ArtificialSources.`C.scala`)
        )

        val dependencies = Map(ProjectToCompile -> Set("parent0", "parent1"))
        checkAfterCleanCompilation(projectsStructure, dependencies, logger = logger) {
          (state: State) =>
            assert(state.build.projects.forall(p => hasPreviousResult(p, state)))
        }
      }
    }

    "Un-necessary projects are not compiled" - {
      logger.quietIfSuccess { logger =>
        val projectsStructures = Map(
          "parent" -> Map("A.scala" -> ArtificialSources.`A.scala`),
          "unrelated" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
          ProjectToCompile -> Map("C.scala" -> ArtificialSources.`C2.scala`)
        )

        val dependencies = Map(ProjectToCompile -> Set("parent"))
        checkAfterCleanCompilation(projectsStructures, dependencies, logger = logger) {
          (state: State) =>
            // The unrelated project should not have been compiled
            assert(noPreviousResult(getProject("unrelated", state), state))
            assert(hasPreviousResult(getProject("parent", state), state))
            assert(hasPreviousResult(getProject(ProjectToCompile, state), state))
        }
      }
    }

    "There is no result when compilation fails" - {
      logger.quietIfError { logger =>
        val projectsStructure = Map(ProjectToCompile -> Map("Error.scala" -> "iwontcompile"))
        checkAfterCleanCompilation(projectsStructure, Map.empty, logger = logger) {
          (state: State) =>
            assert(state.build.projects.forall(p => noPreviousResult(p, state)))
        }
      }
    }
  }

  private def simpleProject(scalaInstance: ScalaInstance, logger: Logger): Unit = {
    val dependencies = Map.empty[String, Set[String]]
    val projectsStructure = Map(ProjectToCompile -> Map("A.scala" -> "object A"))
    // Scala bug to report: removing `(_ => ())` fails to compile.
    checkAfterCleanCompilation(projectsStructure, dependencies, scalaInstance, logger)(_ => ())
  }
}
