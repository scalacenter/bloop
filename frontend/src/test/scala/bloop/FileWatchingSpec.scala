package bloop

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.data.Project
import bloop.engine.Dag
import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import bloop.io.Environment.lineSeparator
import bloop.logging.DebugFilter
import bloop.logging.PublisherLogger
import bloop.logging.RecordingLogger
import bloop.testing.BaseSuite
import bloop.util.TestProject
import bloop.util.TestUtil

import monix.eval.Task
import monix.execution.misc.NonFatal
import monix.reactive.MulticastStrategy
import monix.reactive.Observable

object FileWatchingSpec extends BaseSuite {
  System.setProperty("file-watcher-batch-window-ms", "100")
  test("simulate an incremental compiler session with file watching enabled") {
    TestUtil.withinWorkspace { workspace =>
      import ExecutionContext.ioScheduler
      object Sources {
        val `A.scala` =
          """/A.scala
            |class A {
            |  def foo(s: String) = s.toString
            |}
            |
            |import scala.reflect.macros.blackbox.Context
            |import scala.language.experimental.macros
            |
            |object SleepMacro {
            |  def sleep(): Unit = macro sleepImpl
            |  def sleepImpl(c: Context)(): c.Expr[Unit] = {
            |    import c.universe._
            |    // Sleep for 1 second
            |    Thread.sleep(1000)
            |    reify { () }
            |  }
            |}
          """.stripMargin

        val `B.scala` =
          """/B.scala
            |trait B {
            |  println("Dummy class")
            |}
          """.stripMargin

        val `C.scala` =
          """/C.scala
            |object C extends A with B
          """.stripMargin

        val `D.scala` =
          """/D.scala
            |object D
          """.stripMargin

        val `E.scala` =
          """/E.scala
            |object E
          """.stripMargin

        val `C2.scala` =
          """/C.scala
            |object C2 extends A with B {
            |  SleepMacro.sleep()
            |}
          """.stripMargin

        val `D2.scala` =
          """/D.scala
            |object D {}
          """.stripMargin

        val `D3.scala` =
          """/D.scala
            |object D extends A {
            |  SleepMacro.sleep()
            |}
          """.stripMargin

        val `D4.scala` =
          """/D.scala
            |object D extends A {
            |  println("D4")
            |  SleepMacro.sleep()
            |}
          """.stripMargin

        val `D5.scala` =
          """/D.scala
            |object D extends A {
            |  println("D5")
            |  SleepMacro.sleep()
            |}
          """.stripMargin
      }

      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`B.scala`))
      val `C` =
        TestProject(workspace, "c", List(Sources.`C.scala`, Sources.`D.scala`), List(`A`, `B`))
      val projects = List(`A`, `B`, `C`)

      val initialState = loadState(workspace, projects, new RecordingLogger())
      val compiledState = initialState.compile(`C`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      val (logObserver, logsObservable) =
        Observable.multicast[(String, String)](MulticastStrategy.replay)(ioScheduler)
      val logger = new PublisherLogger(logObserver, DebugFilter.All)

      compiledState.withLogger(logger).compileHandle(`C`, watch = true)

      val HasIterationStoppedMsg = s"Watching ${numberDirsOf(compiledState.getDagFor(`C`))}"
      def waitUntilIteration(totalIterations: Int, duration: Option[Long] = None): Task[Unit] =
        waitUntilWatchIteration(logsObservable, totalIterations, HasIterationStoppedMsg, duration)

      def testValidLatestState: TestState = {
        val state = compiledState.getLatestSavedStateGlobally()
        assert(state.status == ExitStatus.Ok)
        assertValidCompilationState(state, projects)
        state
      }

      TestUtil.await(FiniteDuration(60, TimeUnit.SECONDS), ExecutionContext.ioScheduler) {
        for {
          _ <- waitUntilIteration(1)
          initialWatchedState <- Task(testValidLatestState)

          // Write two events, notifications should be buffered and trigger one compilation
          _ <- Task {
            writeFile(`C`.srcFor("C.scala"), Sources.`C2.scala`)
            writeFile(`C`.srcFor("D.scala"), Sources.`D2.scala`)
          }

          // Write other events that should be processed immediately after the previous batch
          _ <- Task(writeFile(`C`.srcFor("D.scala"), Sources.`D3.scala`))
            .delayExecution(FiniteDuration(200, TimeUnit.MILLISECONDS))
          _ <- Task(writeFile(`C`.srcFor("D.scala"), Sources.`D4.scala`))
            .delayExecution(FiniteDuration(100, TimeUnit.MILLISECONDS))
          _ <- Task(writeFile(`C`.srcFor("D.scala"), Sources.`D5.scala`))
            .delayExecution(FiniteDuration(100, TimeUnit.MILLISECONDS))

          _ <- waitUntilIteration(3, Some(6000L))
          firstWatchedState <- Task(testValidLatestState)

          _ <- Task(writeFile(`C`.baseDir.resolve("E.scala"), Sources.`E.scala`))

          _ <- waitUntilIteration(3, Some(1000L))
          secondWatchedState <- Task(testValidLatestState)

          // Revert to change without macro calls, fourth compilation should happen
          _ <- Task { writeFile(`C`.srcFor("C.scala"), Sources.`C.scala`) }
          _ <- waitUntilIteration(4)
          thirdWatchedState <- Task(testValidLatestState)
        } yield {
          assert(firstWatchedState.status == ExitStatus.Ok)
          assert(secondWatchedState.status == ExitStatus.Ok)
          assert(thirdWatchedState.status == ExitStatus.Ok)
          assertValidCompilationState(thirdWatchedState, projects)

          assert(
            initialWatchedState.getLastSuccessfulResultFor(`C`) !=
              firstWatchedState.getLastSuccessfulResultFor(`C`)
          )

          assert(
            firstWatchedState.getLastSuccessfulResultFor(`C`) ==
              secondWatchedState.getLastSuccessfulResultFor(`C`)
          )

          assert(
            firstWatchedState.getLastSuccessfulResultFor(`C`) !=
              thirdWatchedState.getLastSuccessfulResultFor(`C`)
          )
        }
      }
    }
  }

  flakyTest("support globs", 3) {
    TestUtil.withinWorkspace { workspace =>
      import ExecutionContext.ioScheduler
      object Sources {
        val `A.scala` =
          """/A.scala
            |class A
          """.stripMargin

        val `A2.scala` =
          """/A.scala
            |class A { val foo = 0 }
          """.stripMargin
      }

      val baseProject = TestProject(workspace, "a", sources = List(Sources.`A.scala`))
      val globDirectory = baseProject.config.directory.resolve("src")
      val `A` = baseProject.copy(
        config = baseProject.config.copy(
          sources = Nil,
          sourcesGlobs = Some(
            List(
              Config.SourcesGlobs(
                globDirectory,
                walkDepth = None,
                includes = List("glob:*.scala"),
                excludes = Nil
              )
            )
          )
        )
      )
      val projects = List(`A`)

      val initialState = loadState(workspace, projects, new RecordingLogger())
      val compiledState = initialState.compile(`A`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      val (logObserver, logsObservable) =
        Observable.multicast[(String, String)](MulticastStrategy.replay)(ioScheduler)
      val logger = new PublisherLogger(logObserver, DebugFilter.All)

      compiledState.withLogger(logger).compileHandle(`A`, watch = true)

      val HasIterationStoppedMsg = s"Watching ${numberDirsOf(compiledState.getDagFor(`A`))}"
      def waitUntilIteration(totalIterations: Int, duration: Option[Long] = None): Task[Unit] =
        waitUntilWatchIteration(logsObservable, totalIterations, HasIterationStoppedMsg, duration)

      def testValidLatestState: TestState = {
        val state = compiledState.getLatestSavedStateGlobally()
        assert(state.status == ExitStatus.Ok)
        assertValidCompilationState(state, projects)
        state
      }

      TestUtil.await(FiniteDuration(20, TimeUnit.SECONDS), ExecutionContext.ioScheduler) {
        for {
          _ <- waitUntilIteration(1)
          initialWatchedState <- Task(testValidLatestState)
          _ <- Task(writeFile(AbsolutePath(globDirectory.resolve("A.scala")), Sources.`A2.scala`))
          _ <- waitUntilIteration(2, Some(6000L))
          finalWatchedState <- Task(testValidLatestState)
        } yield {
          assert(initialWatchedState.status == ExitStatus.Ok)
          assert(finalWatchedState.status == ExitStatus.Ok)
        }
      }
    }
  }

  test("don't act on MODIFY events with size == 0 right away") {
    TestUtil.withinWorkspace { workspace =>
      import ExecutionContext.ioScheduler
      object Sources {
        val `A.scala` =
          """/A.scala
            |class A {
            |  def foo(s: String) = s.toString
            |}
          """.stripMargin

        val `B.scala` =
          """/B.scala
            |object B extends A
          """.stripMargin

        val `A2.scala` =
          """/A.scala
          """.stripMargin

        val `A3.scala` =
          """/A.scala
            |class A {
            |  def foo2(s: String) = s.toString
            |}
          """.stripMargin
      }

      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`B.scala`), List(`A`))
      val projects = List(`A`, `B`)

      val initialState = loadState(workspace, projects, new RecordingLogger())
      val compiledState = initialState.compile(`B`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      val (logObserver, logsObservable) =
        Observable.multicast[(String, String)](MulticastStrategy.replay)(ioScheduler)
      val logger = new PublisherLogger(logObserver, DebugFilter.All)

      compiledState.withLogger(logger).compileHandle(`B`, watch = true)

      val HasIterationStoppedMsg = s"Watching ${numberDirsOf(compiledState.getDagFor(`B`))}"
      def waitUntilIteration(totalIterations: Int): Task[Unit] =
        waitUntilWatchIteration(logsObservable, totalIterations, HasIterationStoppedMsg, None)

      def testValidLatestState: TestState = {
        val state = compiledState.getLatestSavedStateGlobally()
        assert(state.status == ExitStatus.Ok)
        assertValidCompilationState(state, projects)
        state
      }

      TestUtil.await(FiniteDuration(20, TimeUnit.SECONDS), ExecutionContext.ioScheduler) {
        for {
          _ <- waitUntilIteration(1)
          _ <- Task(testValidLatestState)
          // Write two events, notifications should be buffered and trigger one compilation
          _ <- Task(writeFile(`A`.srcFor("A.scala"), Sources.`A2.scala`))
          // Write another change with a delay to simulate remote development in VS Code
          _ <- Task(writeFile(`A`.srcFor("A.scala"), Sources.`A3.scala`))
            .delayExecution(FiniteDuration(100, TimeUnit.MILLISECONDS))
          _ <- waitUntilIteration(2)
          firstWatchedState <- Task(testValidLatestState)
          _ <- Task(writeFile(`A`.srcFor("A.scala"), Sources.`A2.scala`))
          _ <- waitUntilIteration(3)
          secondWatchedState <- Task(compiledState.getLatestSavedStateGlobally())
        } yield {
          Predef.assert(firstWatchedState.status == ExitStatus.Ok)
          Predef.assert(secondWatchedState.status == ExitStatus.Ok)
          val targetBPath = TestUtil.universalPath("b/src/B.scala")
          assertNoDiff(
            logger.renderErrors(exceptContaining = "Failed to compile"),
            s"""|[E1] ${targetBPath}:1:18
                |     not found: type A
                |     L1: object B extends A
                |                          ^
                |$targetBPath: L1 [E1]
                |""".stripMargin
          )
        }
      }
    }
  }

  def waitUntilWatchIteration(
      logsObservable: Observable[(String, String)],
      totalIterations: Int,
      targetMsg: String,
      initialDuration: Option[Long]
  ): Task[Unit] = {

    def count(ps: List[(String, String)]) = ps.count(_._2.contains(targetMsg))

    var errorMessage: String = ""
    def waitForIterationFor(duration: FiniteDuration): Task[Unit] = {
      logsObservable
        .takeByTimespan(duration)
        .toListL
        .map { logs =>
          val obtainedIterations = count(logs)
          try assert(totalIterations == obtainedIterations)
          catch {
            case NonFatal(t) =>
              errorMessage =
                logs.map { case (level, log) => s"[$level] $log" }.mkString(lineSeparator)
              throw t
          }
        }
    }

    waitForIterationFor(FiniteDuration(initialDuration.getOrElse(1500L), "ms"))
      .onErrorFallbackTo(waitForIterationFor(FiniteDuration(5000, "ms")))
      .doOnFinish {
        case Some(_) => Task.eval(System.err.println(errorMessage))
        case None => Task.unit
      }
  }

  test("cancel file watcher") {
    TestUtil.withinWorkspace { workspace =>
      import ExecutionContext.ioScheduler
      object Sources {
        val `A.scala` =
          """/A.scala
            |class A {
            |  def foo(s: String) = s.toString
            |}
          """.stripMargin
      }

      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`))
      val projects = List(`A`)

      val initialState = loadState(workspace, projects, new RecordingLogger())
      val compiledState = initialState.compile(`A`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      val (logObserver, logsObservable) =
        Observable.multicast[(String, String)](MulticastStrategy.replay)(ioScheduler)
      val logger = new PublisherLogger(logObserver, DebugFilter.All)

      val futureWatchedCompiledState =
        compiledState.withLogger(logger).compileHandle(`A`, watch = true)

      val HasIterationStoppedMsg = s"Watching ${numberDirsOf(compiledState.getDagFor(`A`))}"
      def waitUntilIteration(totalIterations: Int): Task[Unit] =
        waitUntilWatchIteration(logsObservable, totalIterations, HasIterationStoppedMsg, None)

      TestUtil.await(FiniteDuration(15, TimeUnit.SECONDS)) {
        for {
          _ <- waitUntilIteration(1)
          initialWatchedState <- Task(compiledState.getLatestSavedStateGlobally())
          _ <- Task(futureWatchedCompiledState.cancel())
          _ <- waitUntilIteration(1)
        } yield {
          assert(initialWatchedState.status == ExitStatus.Ok)
        }
      }

      scala.concurrent.Await
        .result(futureWatchedCompiledState, FiniteDuration(1, TimeUnit.SECONDS))
    }
  }

  private def numberDirsOf(dag: Dag[Project]): Int = {
    val reachable = Dag.dfs(dag)
    val allSources = reachable.iterator.flatMap(_.sources.toList).map(_.underlying).toList
    allSources.filter { p =>
      val s = p.toString
      java.nio.file.Files.exists(p) && !s.endsWith(".scala") && !s.endsWith(".java")
    }.length
  }

  ignore("playground for monix primitives") {
    import scala.concurrent.duration._
    import monix.execution.Scheduler.Implicits.global
    val (observer, observable) =
      Observable.multicast[String](MulticastStrategy.publish)

    val received = new StringBuilder()

    import bloop.util.monix.FoldLeftAsyncConsumer
    val slowConsumer = FoldLeftAsyncConsumer.consume[Unit, Seq[String]](()) {
      case (_: Unit, msg: Seq[String]) =>
        Task {
          Thread.sleep(40)
          received
            .++=("Received ")
            .++=(msg.mkString(", "))
            .++=(lineSeparator)
          ()
        }
    }

    import bloop.util.monix.BloopBufferTimedObservable

    val consumingTask =
      new BloopBufferTimedObservable(observable, 40.millis, 0)
        // observable
        //  .debounce(40.millis)
        .collect { case s if !s.isEmpty => s }
        // .whileBusyBuffer(OverflowStrategy.Unbounded)
        .whileBusyDropEventsAndSignal(_ => List("boo"))
        .consumeWith(slowConsumer)
    val createEvents = Task {
      Thread.sleep(60)
      observer.onNext("a")
      Thread.sleep(45)
      observer.onNext("b")
      Thread.sleep(20)
      observer.onNext("c")

      /*
      Thread.sleep(200)
      observer.onNext("b")
      Thread.sleep(60)
      observer.onNext("c")
       */
      Thread.sleep(1500)
      observer.onComplete()
      ()
    }

    val f = Task.mapBoth(consumingTask, createEvents)((_: Unit, _: Unit) => ()).runAsync
    scala.concurrent.Await.result(f, 2.second)
    println(received.toString)
  }
}
