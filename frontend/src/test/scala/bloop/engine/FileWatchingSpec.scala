package bloop.engine

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import bloop.data.Project
import bloop.cli.Commands
import bloop.logging.{DebugFilter, Logger, PublisherLogger}
import bloop.exec.JavaEnv
import bloop.io.AbsolutePath
import bloop.io.Paths.delete
import bloop.tasks.{CompilationHelpers, TestUtil}
import bloop.tasks.TestUtil.{RootProject, withState}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{MulticastStrategy, Observable}
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.Assert.assertTrue

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, FiniteDuration}

@Category(Array(classOf[bloop.FastTests]))
class FileWatchingSpec {
  object ArtificialSources {
    val `A.scala` = "package p0\ntrait A"
    val `B.scala` = "package p1\ntrait B"
    val `C.scala` = "package p2\nclass C extends p0.A with p1.B"
  }

  @Test
  def testFileWatchingForCompile(): Unit = {
    val structures = Map(
      "parent0" -> Map("A.scala" -> ArtificialSources.`A.scala`),
      "parent1" -> Map("B.scala" -> ArtificialSources.`B.scala`),
      RootProject -> Map("C.scala" -> ArtificialSources.`C.scala`)
    )

    val dependencies = Map(RootProject -> Set("parent0", "parent1"))
    val instance = CompilationHelpers.scalaInstance
    withState(structures, dependencies, scalaInstance = instance, javaEnv = JavaEnv.default) {
      (state: State) =>
        val load = (logger: Logger) => state.copy(logger = logger)
        val cmd = Run(Commands.Compile(RootProject, watch = true))
        val messagesToCheck = List(
          s"Compiling ${RootProject}" -> 3,
          "Compiling parent0" -> 1,
          "Compiling parent1" -> 1,
        )

        checkFileWatchingIteration(load, RootProject, cmd, messagesToCheck)
    }
  }

  @Test
  def testFileWatchingForTests(): Unit = {
    val targetProject = "with-tests-test"
    val load = (logger: Logger) => TestUtil.loadTestProject("with-tests").copy(logger = logger)
    val runTest = Run(Commands.Test(targetProject, watch = true))
    val messagesToCheck = List(
      "Compiling with-tests" -> 5,
      s"Compiling ${targetProject}" -> 3,
      "is very personal" -> 3,
      "+ Greeting.is personal: OK" -> 3,
      "- should be very personal" -> 3,
      "Total for specification Specs2Test" -> 3
    )

    checkFileWatchingIteration(load, targetProject, runTest, messagesToCheck)
  }

  /**
   * Adds non-existing source files and single source files to the project to make sure
   * that the file watcher handles them correctly. Source files/dirs that don't exist must
   * be ignored, and single source files that do exist require the watching of its parent.
   */
  private def addNonExistingAndSingleFileSourceTo(project: Project): (Project, AbsolutePath) = {
    val currentSources = project.sources
    currentSources.headOption match {
      case Some(source) =>
        val fakeSource = source.getParent.resolve("fake-source-dir-scala")
        val singleFile = project.baseDirectory.resolve("x.scala")
        Files.write(singleFile.underlying, "trait X".getBytes(StandardCharsets.UTF_8))
        project.copy(sources = currentSources ++ List(fakeSource, singleFile)) -> singleFile
      case None => sys.error(s"Project ${project.name} has no source directories/files!")
    }
  }

  private def numberDirsOf(project: Project, state: State): Int = {
    val reachable = Dag.dfs(state.build.getDagFor(project))
    val allSources = reachable.iterator.flatMap(_.sources.toList).map(_.underlying).toList
    allSources.filter { p =>
      val s = p.toString
      Files.exists(p) && !s.endsWith(".scala") && !s.endsWith(".java")
    }.length
  }

  private def checkFileWatchingIteration(
      load: Logger => State,
      targetProject: String,
      commandToRun: Action,
      targetMessages: List[(String, Int)],
      debug: Boolean = false
  ): Unit = {
    import ExecutionContext.ioScheduler
    val (observer, observable) =
      Observable.multicast[(String, String)](MulticastStrategy.publish)(ioScheduler)
    val logger = new PublisherLogger(observer, debug = debug, DebugFilter.All)

    // Let's modify the project to add special sources to check the right behaviour of the watcher
    val (state, singleFile) = {
      val state0 = load(logger)
      val (project, singleFile) = addNonExistingAndSingleFileSourceTo(
        state0.build.getProjectFor(targetProject).get)
      val newProjects =
        state0.build.projects.mapConserve(p => if (p.name == targetProject) project else p)
      state0.copy(build = state0.build.copy(projects = newProjects)) -> singleFile
    }

    val project = state.build.getProjectFor(targetProject).get
    val targetMsg = s"Watching ${numberDirsOf(project, state)}"
    def isIterationOver(observable: Observable[(String, String)], iteration: Int): Task[Unit] = {
      observable
        .existsL {
          case (level, msg) => level == "info" && msg.contains(targetMsg)
        }
        .map { success =>
          if (success) ()
          else sys.error(s"Missing '$targetMsg' in iteration $iteration")
        }
    }

    // Deletion doesn't trigger recompilation -- done to avoid file from previous test run
    val existingSourceDir = project.sources.collectFirst { case d if d.exists => d }.get
    val newSource = existingSourceDir.resolve("D.scala")
    if (Files.exists(newSource.underlying)) delete(newSource)
    Thread.sleep(50)

    val runTest = TestUtil.interpreterTask(commandToRun, state)
    val testFuture = runTest.runAsync(ExecutionContext.scheduler)

    val checkTests = isIterationOver(observable, 1).flatMap { _ =>
      // Ugly, but we need to wait a little bit here so that file watching is active
      Thread.sleep(200)

      // Write the contents of a source back to the same source and force another test execution
      //Files.write(singleFile.underlying, "object Hello".getBytes("UTF-8"))
      Files.write(newSource.underlying, "object ForceRecompilation {}".getBytes("UTF-8"))
      Thread.sleep(50)

      isIterationOver(observable, 2).flatMap { _ =>
        // Write the contents of a source back to the same source and force another test execution
        Files.write(singleFile.underlying, "object ForceRecompilation2 {}".getBytes("UTF-8"))
        Thread.sleep(50)

        isIterationOver(observable, 3).map { _ =>
          testFuture.cancel()
          val infos = logger.filterMessageByLabel("info")
          println(infos.mkString("\n"))
          targetMessages.foreach {
            case (msg, count) =>
              val times = infos.count(_.contains(msg))
              assertTrue(s"Predicate '$msg' found $times times, expected $count", times == count)
          }
        }
      }
    }

    val t = Task.zip2(Task.fromFuture(testFuture), checkTests).doOnCancel(Task(testFuture.cancel()))
    TestUtil.blockOnTask(t, 30.toLong)
    ()
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
        val watchTask = Interpreter.watch(rootProject, state)(simulation _)
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
