package bloop.engine

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.util.concurrent.TimeUnit

import bloop.Project
import bloop.cli.{CliOptions, Commands, ExitStatus}
import bloop.logging.BloopLogger
import bloop.exec.JavaEnv
import bloop.tasks.{CompilationHelpers, TestUtil}
import bloop.tasks.TestUtil.{RootProject, noPreviousResult, withState}
import monix.eval.Task
import monix.execution.Scheduler
import org.junit.Test
import org.junit.experimental.categories.Category

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, FiniteDuration}

@Category(Array(classOf[bloop.FastTests]))
class FileWatchingSpec {
  @scala.annotation.tailrec
  final def readCompilingLines(target: Int, msg: String, out: ByteArrayOutputStream): Int = {
    Thread.sleep(100) // Wait 150ms for the OS's file system
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

  def addNonExistingSources(project: Project): Project = {
    val currentSources = project.sources
    currentSources.headOption match {
      case Some(source) =>
        val fakeSource = source.getParent.resolve("fake-source-dir-scala")
        project.copy(sources = currentSources ++ Array(fakeSource))
      case None => project
    }
  }

  type FileWatchingContext = (State, Project, ByteArrayOutputStream)
  def testFileWatcher(state0: State, projectName: String)(
      workerAction: FileWatchingContext => Unit,
      testAction: (FileWatchingContext, Thread) => Unit): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration
    implicit val scheduler = ExecutionContext.scheduler
    val projects0 = state0.build.projects
    val rootProject0 = projects0
      .find(_.name == projectName)
      .getOrElse(sys.error(s"Project $projectName could not be found!"))
    // Add non-existing sources on purpose to the project to ensure it doesn't crash
    val rootProject = addNonExistingSources(rootProject0)
    val cleanAction = Run(Commands.Clean(rootProject.name :: Nil), Exit(ExitStatus.Ok))
    val state = TestUtil.blockingExecute(cleanAction, state0)
    val projects = state.build.projects
    assert(projects.forall(p => noPreviousResult(p, state)))

    // The worker thread runs the watched compilation
    val bloopOut = new ByteArrayOutputStream()
    val ctx: FileWatchingContext = (state, rootProject, bloopOut)
    val workerThread = new Thread { override def run(): Unit = workerAction(ctx) }
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
        val loggerName = UUID.randomUUID().toString
        val newLogger = BloopLogger.at(loggerName, newOut, newOut)
        val newState = state.copy(logger = newLogger)
        val commonOptions = cliOptions0.common.copy(out = newOut)
        val cliOptions = cliOptions0.copy(common = commonOptions)
        val cmd = Commands.Compile(project.name, watch = true, cliOptions = cliOptions)
        TestUtil.blockingExecute(Run(cmd), newState)
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
        val newSource = project.sources.head.resolve("D.scala").underlying
        Files.write(newSource, "object ForceRecompilation {}".getBytes("UTF-8"))
        // Wait for #2 compilation to finish
        readCompilingLines(4, "Compiling 1 Scala source to", bloopOut)
        // Finish source file watching
        workerThread.interrupt()
    }

    val dependencies = Map(RootProject -> Set("parent0", "parent1"))
    val instance = CompilationHelpers.scalaInstance
    val javaEnv = JavaEnv.default
    withState(structures, dependencies, scalaInstance = instance, javaEnv = javaEnv) {
      (state: State) =>
        testFileWatcher(state, RootProject)(workerAction, testAction)
    }
  }

  @Test
  def watchTest(): Unit = {
    val TestProjectName = "with-tests"
    val testProject = s"$TestProjectName-test"
    val state0 = TestUtil.loadTestProject(TestProjectName)
    val commonOptions = state0.commonOptions.copy(env = TestUtil.runAndTestProperties)
    val state = state0.copy(commonOptions = commonOptions)

    val workerAction: FileWatchingContext => Unit = {
      case (state, project, bloopOut) =>
        val cliOptions0 = CliOptions.default
        val newOut = new PrintStream(bloopOut)
        val loggerName = UUID.randomUUID().toString
        val newLogger = BloopLogger.at(loggerName, newOut, newOut).asVerbose
        val newState = state.copy(logger = newLogger)
        val commonOptions1 = commonOptions.copy(out = newOut)
        val cliOptions = cliOptions0.copy(common = commonOptions1)
        val cmd = Commands.Test(project.name, watch = true, cliOptions = cliOptions)
        TestUtil.blockingExecute(Run(cmd), newState)
        ()
    }

    val testAction: (FileWatchingContext, Thread) => Unit = {
      case ((state, project, bloopOut), workerThread) =>
        // Start the compilation
        workerThread.start()

        // Create non-existing source dirs in tests Xhttps://github.com/scalacenter/bloop/pull/471
        project.sources.foreach { a =>
          val p = a.underlying
          val s = p.toString
          if (!Files.exists(p) && !s.endsWith(".scala") && !s.endsWith(".java"))
            Files.createDirectories(p)
        }

        // Deletion doesn't trigger recompilation -- done to avoid file from previous test run
        val newSource = project.sources.head.resolve("D.scala").underlying
        if (Files.exists(newSource)) TestUtil.delete(newSource)

        // Wait for #1 compilation to finish
        readCompilingLines(1, "Compiling 1 Scala source to", bloopOut)
        readCompilingLines(1, "Compiling 6 Scala sources to", bloopOut)
        readCompilingLines(1, "+ is very personal", bloopOut)
        readCompilingLines(1, "+ Greeting.is personal: OK", bloopOut)
        readCompilingLines(1, "- should be very personal", bloopOut)
        readCompilingLines(1, "Total for specification Specs2Test", bloopOut)
        readCompilingLines(2, "Test server has been successfully closed.", bloopOut)
        readCompilingLines(1, "Watching 8 directories... (press C-c to interrupt)", bloopOut)

        // Write the contents of a source back to the same source
        Files.write(newSource, "object ForceRecompilation {}".getBytes("UTF-8"))

        // Wait for #2 compilation to finish
        readCompilingLines(2, "Compiling 1 Scala source to", bloopOut)
        readCompilingLines(1, "Compiling 6 Scala sources to", bloopOut)
        readCompilingLines(2, "+ is very personal", bloopOut)
        readCompilingLines(2, "+ Greeting.is personal: OK", bloopOut)
        readCompilingLines(2, "- should be very personal", bloopOut)
        readCompilingLines(2, "Total for specification Specs2Test", bloopOut)
        readCompilingLines(4, "Test server has been successfully closed.", bloopOut)

        // Finish source file watching
        workerThread.interrupt()
    }

    testFileWatcher(state, testProject)(workerAction, testAction)
  }

  @Test
  def cancelFileWatching(): Unit = {
    def simulation(state: State): Task[State] = Task {
      println("Simulating compilation...")
      Thread.sleep(1000)
      println("Simulating completed.")
      state
    }

    val javaEnv = JavaEnv.default
    val instance = CompilationHelpers.scalaInstance
    val structures = Map(RootProject -> Map("A.scala" -> ArtificialSources.`A.scala`))
    withState(structures, Map.empty, scalaInstance = instance, javaEnv = javaEnv) {
      (state: State) =>
        val rootProject = state.build.getProjectFor(RootProject).get
        val watchTask = Interpreter.watch(rootProject, state, simulation _)
        val s1 = Scheduler.computation(parallelism = 2)
        val handle = watchTask.runAsync(ExecutionContext.scheduler)

        val waitForWatching = Task(Await.result(handle, Duration.Inf))
        val waitAndCancelWatching =
          Task(handle.cancel()).delayExecution(FiniteDuration(2, TimeUnit.SECONDS))
        val waitWatchingHandle = waitForWatching.runAsync(s1)
        val waitCancelHandle = waitAndCancelWatching.runAsync(s1)

        try Await.result(waitCancelHandle, FiniteDuration(3, TimeUnit.SECONDS))
        finally println(s"Cancellation was executed ${waitCancelHandle.isCompleted}")
        try Await.result(waitWatchingHandle, FiniteDuration(5, TimeUnit.SECONDS))
        finally println(s"Watching task was completed ${waitWatchingHandle.isCompleted}")
    }
    ()
  }
}
