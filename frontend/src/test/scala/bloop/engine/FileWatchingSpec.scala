package bloop.engine

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.Files

import bloop.Project
import bloop.cli.{CliOptions, Commands}
import bloop.tasks.{CompilationHelpers, ProjectHelpers}
import bloop.tasks.ProjectHelpers.{RootProject, noPreviousResult, withState}
import org.junit.Test

class FileWatchingSpec {
  @scala.annotation.tailrec
  final def readCompilingLines(target: Int, msg: String, out: ByteArrayOutputStream): Int = {
    Thread.sleep(100) // Wait 100ms for the OS's file system
    val allContents = out.toString("UTF-8")
    val allLines = allContents.split(System.lineSeparator())
    val compiled = allLines.count(_.replaceAll("\u001B\\[[;\\d]*m", "").contains(msg))
    if (compiled == target) compiled
    else readCompilingLines(target, msg, out)
  }

  // We have here our own artificial sources
  object ArtificialSources {
    val `A.scala` = "package p0\ntrait A"
    val `B.scala` = "package p1\ntrait B"
    val `C.scala` = "package p2\nclass C extends p0.A with p1.B"
  }

  type FileWatchingContext = (State, Project, ByteArrayOutputStream)
  def testFileWatcher(state: State, projectName: String)(
      workerAction: FileWatchingContext => Unit,
      testAction: (FileWatchingContext, Thread) => Unit): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration
    val projects = state.build.projects
    val rootProject = projects
      .find(_.name == projectName)
      .getOrElse(sys.error(s"Project $projectName could not be found!"))
    assert(projects.forall(p => noPreviousResult(p, state)))

    // The worker thread runs the watched compilation
    val bloopOut = new ByteArrayOutputStream()
    val ctx: FileWatchingContext = (state, rootProject, bloopOut)
    val workerThread = new Thread { override def run(): Unit = workerAction(ctx) }
    implicit val context = state.executionContext
    val testFuture = scala.concurrent.Future { testAction(ctx, workerThread) }
    try Await.ready(testFuture, duration.Duration(15, duration.SECONDS))
    catch { case t: Throwable => println(s"LOGS WERE ${bloopOut.toString("UTF-8")}"); throw t }
    ()
  }

  @Test
  def watchCompile(): Unit = {
    val structures = Map(
      "parent0" -> Map("A.scala" -> ArtificialSources.`A.scala`),
      "parent1" -> Map("B.scala" -> ArtificialSources.`B.scala`),
      RootProject -> Map("C.scala" -> ArtificialSources.`C.scala`)
    )

    val workerAction: FileWatchingContext => Unit = {
      case (state, project, bloopOut) =>
        val cliOptions0 = CliOptions.default
        val newOut = new PrintStream(bloopOut)
        val commonOptions = cliOptions0.common.copy(out = newOut)
        val cliOptions = cliOptions0.copy(common = commonOptions)
        val cmd = Commands.Compile(project.name, watch = true, cliOptions = cliOptions)
        Interpreter.execute(Run(cmd), state)
        ()
    }

    val testAction: (FileWatchingContext, Thread) => Unit = {
      case ((state, project, bloopOut), workerThread) =>
        // Start the compilation
        workerThread.start()
        // Wait for #1 compilation to finish
        readCompilingLines(3, "Compiling 1 Scala source to", bloopOut)
        // Let's wait half a second so that file watching mode is enabled
        Thread.sleep(1500)

        // Write the contents of a new file to force recompilation
        val newSource = project.sourceDirectories.head.resolve("D.scala").underlying
        Files.write(newSource, "object ForceRecompilation {}".getBytes("UTF-8"))
        // Wait for #2 compilation to finish
        readCompilingLines(4, "Compiling 1 Scala source to", bloopOut)
        // Finish source file watching
        workerThread.interrupt()
    }

    val dependencies = Map(RootProject -> Set("parent0", "parent1"))
    val instance = CompilationHelpers.scalaInstance
    withState(structures, dependencies, scalaInstance = instance) { (state: State) =>
      testFileWatcher(state, RootProject)(workerAction, testAction)
    }
  }

  @Test
  def watchTest(): Unit = {
    val TestProjectName = "with-tests"
    val testProject = s"$TestProjectName-test"
    val state = ProjectHelpers.loadTestProject(TestProjectName)

    val workerAction: FileWatchingContext => Unit = {
      case (state, project, bloopOut) =>
        val cliOptions0 = CliOptions.default
        val newOut = new PrintStream(bloopOut)
        val commonOptions = cliOptions0.common.copy(out = newOut)
        val cliOptions = cliOptions0.copy(common = commonOptions, verbose = true)
        val cmd = Commands.Test(project.name, watch = true, cliOptions = cliOptions)
        Interpreter.execute(Run(cmd), state)
        ()
    }

    val testAction: (FileWatchingContext, Thread) => Unit = {
      case ((state, project, bloopOut), workerThread) =>
        // Start the compilation
        workerThread.start()

        // Deletion doesn't trigger recompilation -- done to avoid file from previous test run
        val newSource = project.sourceDirectories.head.resolve("D.scala").underlying
        if (Files.exists(newSource)) ProjectHelpers.delete(newSource)

        // Wait for #1 compilation to finish
        readCompilingLines(1, "Compiling 1 Scala source to", bloopOut)
        readCompilingLines(1, "Compiling 4 Scala sources to", bloopOut)
        readCompilingLines(1, "+ is very personal", bloopOut)
        readCompilingLines(1, "+ Greeting.is personal: OK", bloopOut)
        readCompilingLines(1, "- should be very personal", bloopOut)
        Thread.sleep(1500)

        // Write the contents of a source back to the same source
        Files.write(newSource, "object ForceRecompilation {}".getBytes("UTF-8"))

        // Wait for #2 compilation to finish
        readCompilingLines(2, "Compiling 1 Scala source to", bloopOut)
        readCompilingLines(1, "Compiling 4 Scala sources to", bloopOut)
        readCompilingLines(2, "+ is very personal", bloopOut)
        readCompilingLines(2, "+ Greeting.is personal: OK", bloopOut)
        readCompilingLines(2, "- should be very personal", bloopOut)

        // Finish source file watching
        workerThread.interrupt()
    }

    testFileWatcher(state, testProject)(workerAction, testAction)
  }
}
