package bloop.tasks

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, FileOutputStream, InputStreamReader, PipedInputStream, PipedOutputStream, PrintStream}
import java.nio.file.Files

import utest._
import bloop.{Project, ScalaInstance}
import bloop.cli.{CliOptions, Commands, ExitStatus}
import bloop.engine.{Exit, Interpreter, Run, State}
import bloop.logging.Logger
import org.apache.logging.log4j.core.appender.OutputStreamManager

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
    withState(structures, dependencies, logger, scalaInstance = scalaInstance) { (state: State) =>
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

    "Watch compile one project with two dependencies" - {
      val structures = Map(
        "parent0" -> Map("A.scala" -> ArtificialSources.`A.scala`),
        "parent1" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
        ProjectToCompile -> Map("C.scala" -> ArtificialSources.`C.scala`)
      )

      val dependencies = Map(ProjectToCompile -> Set("parent0", "parent1"))
      val instance = CompilationHelpers.scalaInstance

      withState(structures, dependencies, logger, scalaInstance = instance) { (state: State) =>
        val projects = state.build.projects
        val rootProject = projects
          .find(_.name == ProjectToCompile)
          .getOrElse(sys.error(s"Project $ProjectToCompile could not be found!"))
        assert(projects.forall(p => noPreviousResult(p, state)))

        import java.nio.charset.StandardCharsets.UTF_8
        val newIn = new PipedInputStream()
        val testWriter = new PipedOutputStream(newIn)
        val bloopOut = new ByteArrayOutputStream()
        val newOut = new PrintStream(bloopOut)

        // The worker thread runs the watched compilation
        val workerThread = new Thread {
          override def run(): Unit = {
            val project = getProject(ProjectToCompile, state)
            val cliOptions0 = CliOptions.default
            val commonOptions = cliOptions0.common.copy(out = newOut)
            val cliOptions = cliOptions0.copy(common = commonOptions)
            val cmd = Commands.Compile(ProjectToCompile, watch = true, cliOptions = cliOptions)
            val action = Run(cmd, Exit(ExitStatus.Ok))
            Interpreter.execute(action, state)
            ()
          }
        }


        @scala.annotation.tailrec
        def readCompilingLines(target: Int): Int = {
          Thread.sleep(100) // Wait 10ms for the OS's file system
          val allContents = bloopOut.toString("UTF-8")
          val allLines = allContents.split(System.lineSeparator())
          val compiled = allLines.count(_.contains("Compiling 1 Scala source to"))
          if (compiled == target) compiled
          else readCompilingLines(target)
        }

        implicit val context = state.executionContext
        val testAction = scala.concurrent.Future {
          // Start the compilation
          workerThread.start()

          println("JUST STARTED")

          // Wait for #1 compilation to finish
          readCompilingLines(3)

          println("FIRST COMPILATION DETECTED")

          // Write the contents of a source back to the same source
          val (fileName, fileContent) = structures(ProjectToCompile).head
          val sourceA = rootProject.sourceDirectories.head.resolve(fileName).underlying
          Files.write(sourceA, fileContent.getBytes(UTF_8))

          // Wait for #2 compilation to finish
          readCompilingLines(4)

          // Finish source file watching
          //testWriter.write("\r\n".getBytes(UTF_8))
          workerThread.interrupt()
        }

        import scala.concurrent.Await
        import scala.concurrent.duration
        Await.ready(testAction, duration.Duration(20, duration.SECONDS))
        if (!testAction.isCompleted)
          sys.error("File watching failed!")
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
