package bloop.tasks

import java.io.{ByteArrayOutputStream, PipedInputStream, PipedOutputStream, PrintStream}
import java.nio.file.Files

import utest._
import bloop.{Project, ScalaInstance}
import bloop.cli.{CliOptions, Commands, ExitStatus}
import bloop.engine.{Exit, Interpreter, Run, State}
import bloop.logging.Logger

object CompilationTaskTest extends TestSuite {
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
  private final val Ok = Exit(ExitStatus.Ok)
  def checkAfterCleanCompilation(
      structures: Map[String, Map[String, String]],
      dependencies: Map[String, Set[String]],
      scalaInstance: ScalaInstance = CompilationHelpers.scalaInstance,
      quiet: Boolean = false,
      failure: Boolean = false)(afterCompile: State => Unit = (_ => ())) = {
    withState(structures, dependencies, scalaInstance = scalaInstance) { (state: State) =>
      def action(state: State): Unit = {
        // Check that this is a clean compile!
        val projects = state.build.projects
        assert(projects.forall(p => noPreviousResult(p, state)))
        val project = getProject(ProjectToCompile, state)
        val action = Run(Commands.Compile(ProjectToCompile, incremental = true), Ok)
        val compiledState = Interpreter.execute(action, state)
        afterCompile(compiledState)
      }

      val logger = state.logger
      if (quiet) logger.quietIfSuccess(newLogger => action(state.copy(logger = newLogger)))
      else if (failure) logger.quietIfError(newLogger => action(state.copy(logger = newLogger)))
      else action(state)
    }
  }

  val tests = Tests {
    "compile an empty project" - {
      val projectStructures = Map(ProjectToCompile -> Map.empty[String, String])
      val dependencies = Map.empty[String, Set[String]]
      checkAfterCleanCompilation(projectStructures, dependencies, quiet = true) { (state: State) =>
        val targetProject = getProject(ProjectToCompile, state)
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
        ProjectToCompile -> Map("B.scala" -> ArtificialSources.`B.scala`)
      )

      val dependencies = Map(ProjectToCompile -> Set("parent"))
      checkAfterCleanCompilation(projectsStructure, dependencies, quiet = true) { (state: State) =>
        assert(state.build.projects.forall(p => hasPreviousResult(p, state)))
      }
    }

    "Compile one project with two dependencies" - {
      val projectsStructure = Map(
        "parent0" -> Map("A.scala" -> ArtificialSources.`A.scala`),
        "parent1" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
        ProjectToCompile -> Map("C.scala" -> ArtificialSources.`C.scala`)
      )

      val dependencies = Map(ProjectToCompile -> Set("parent0", "parent1"))
      checkAfterCleanCompilation(projectsStructure, dependencies, quiet = true) { (state: State) =>
        assert(state.build.projects.forall(p => hasPreviousResult(p, state)))
      }
    }

    @scala.annotation.tailrec
    def readCompilingLines(target: Int, out: ByteArrayOutputStream): Int = {
      Thread.sleep(100) // Wait 10ms for the OS's file system
      val allContents = out.toString("UTF-8")
      val allLines = allContents.split(System.lineSeparator())
      val compiled = allLines.count(_.contains("Compiling 1 Scala source to"))
      if (compiled == target) compiled
      else readCompilingLines(target, out)
    }

    "Watch compile one project with two dependencies" - {
      val structures = Map(
        "parent0" -> Map("A.scala" -> ArtificialSources.`A.scala`),
        "parent1" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
        ProjectToCompile -> Map("C.scala" -> ArtificialSources.`C.scala`)
      )

      val dependencies = Map(ProjectToCompile -> Set("parent0", "parent1"))
      val instance = CompilationHelpers.scalaInstance

      withState(structures, dependencies, scalaInstance = instance) { (state: State) =>
        val projects = state.build.projects
        val rootProject = projects
          .find(_.name == ProjectToCompile)
          .getOrElse(sys.error(s"Project $ProjectToCompile could not be found!"))
        assert(projects.forall(p => noPreviousResult(p, state)))

        // The worker thread runs the watched compilation
        val bloopOut = new ByteArrayOutputStream()
        val newOut = new PrintStream(bloopOut)
        val workerThread = new Thread {
          override def run(): Unit = {
            val project = getProject(ProjectToCompile, state)
            val cliOptions0 = CliOptions.default
            val commonOptions = cliOptions0.common.copy(out = newOut)
            val cliOptions = cliOptions0.copy(common = commonOptions, verbose = true)
            val cmd = Commands.Compile(ProjectToCompile, watch = true, cliOptions = cliOptions)
            val action = Run(cmd, Exit(ExitStatus.Ok))
            Interpreter.execute(action, state)
            ()
          }
        }

        implicit val context = state.executionContext
        val testAction = scala.concurrent.Future {
          // Start the compilation
          workerThread.start()
          // Wait for #1 compilation to finish
          readCompilingLines(3, bloopOut)
          // Let's wait 1 second so that file watching mode is enabled
          Thread.sleep(1000)

          // Write the contents of a source back to the same source
          val newSource = rootProject.sourceDirectories.head.resolve("D.scala").underlying
          Files.write(newSource, "object ForceRecompilation {}".getBytes("UTF-8"))
          // Wait for #2 compilation to finish
          readCompilingLines(4, bloopOut)
          // Finish source file watching
          workerThread.interrupt()
        }

        import scala.concurrent.Await
        import scala.concurrent.duration
        Await.ready(testAction, duration.Duration(10, duration.SECONDS))
      }
    }

    "Un-necessary projects are not compiled" - {
      val projectsStructures = Map(
        "parent" -> Map("A.scala" -> ArtificialSources.`A.scala`),
        "unrelated" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
        ProjectToCompile -> Map("C.scala" -> ArtificialSources.`C2.scala`)
      )

      val dependencies = Map(ProjectToCompile -> Set("parent"))
      checkAfterCleanCompilation(projectsStructures, dependencies, quiet = true) { (state: State) =>
        // The unrelated project should not have been compiled
        assert(noPreviousResult(getProject("unrelated", state), state))
        assert(hasPreviousResult(getProject("parent", state), state))
        assert(hasPreviousResult(getProject(ProjectToCompile, state), state))
      }
    }

    "There is no result when compilation fails" - {
      val projectsStructure = Map(ProjectToCompile -> Map("Error.scala" -> "iwontcompile"))
      checkAfterCleanCompilation(projectsStructure, Map.empty, failure = true) { (state: State) =>
        assert(state.build.projects.forall(p => noPreviousResult(p, state)))
      }
    }
  }

  private def simpleProject(scalaInstance: ScalaInstance): Unit = {
    val dependencies = Map.empty[String, Set[String]]
    val structures = Map(ProjectToCompile -> Map("A.scala" -> "object A"))
    // Scala bug to report: removing `(_ => ())` fails to compile.
    checkAfterCleanCompilation(structures, dependencies, scalaInstance, quiet = true)(_ => ())
  }
}
