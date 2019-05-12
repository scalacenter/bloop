package bloop

import bloop.testing.BaseSuite
import bloop.config.Config
import bloop.data.Project
import bloop.io.{AbsolutePath, Paths => BloopPaths}
import bloop.logging.{RecordingLogger, PublisherLogger, DebugFilter}
import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Feedback, Run, State, ExecutionContext, Dag, Build}
import bloop.engine.caches.ResultsCache
import bloop.util.{TestProject, TestUtil, BuildUtil}

import monix.eval.Task
import monix.reactive.{Observable, MulticastStrategy}

import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

object FileWatchingSpec extends BaseSuite {
  test("simulate an incremental compiler session with file watching enabled") {
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

        val `C2.scala` =
          """/C.scala
            |object C2 extends A with B
          """.stripMargin

        val `D2.scala` =
          """/D.scala
            |object D extends A
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
      val logger = new PublisherLogger(logObserver, debug = true, DebugFilter.All)

      val futureWatchedCompiledState =
        compiledState.withLogger(logger).compileHandle(`C`, watch = true)

      val HasIterationStoppedMsg = s"Watching ${numberDirsOf(compiledState.getDagFor(`C`))}"
      def waitUntilIteration(totalIterations: Int): Task[Unit] =
        waitUntilWatchIteration(logsObservable, totalIterations, HasIterationStoppedMsg)

      def testValidLatestState: TestState = {
        val state = compiledState.getLatestSavedStateGlobally()
        assert(state.status == ExitStatus.Ok)
        assertValidCompilationState(state, projects)
        state
      }

      TestUtil.await(FiniteDuration(10, TimeUnit.SECONDS)) {
        for {
          _ <- waitUntilIteration(1)
          initialWatchedState <- Task(testValidLatestState)
          _ <- Task(writeFile(`C`.srcFor("C.scala"), Sources.`C2.scala`))
          //_ <- Task(writeFile(`C`.srcFor("C.scala"), Sources.`C2.scala`))
          _ <- Task(writeFile(`C`.srcFor("D.scala"), Sources.`D2.scala`))
          //_ <- Task(writeFile(`C`.srcFor("D.scala"), Sources.`D2.scala`))
          _ <- waitUntilIteration(2)
          firstWatchedState <- Task(testValidLatestState)
          _ <- Task(writeFile(`C`.baseDir.resolve("E.scala"), Sources.`C.scala`))
          _ <- waitUntilIteration(2)
          secondWatchedState <- Task(testValidLatestState)
        } yield {

          assert(secondWatchedState.status == ExitStatus.Ok)
          assertValidCompilationState(secondWatchedState, projects)

          assert(
            initialWatchedState.getLastSuccessfulResultFor(`C`) !=
              firstWatchedState.getLastSuccessfulResultFor(`C`)
          )

          assert(
            firstWatchedState.getLastSuccessfulResultFor(`C`) ==
              secondWatchedState.getLastSuccessfulResultFor(`C`)
          )
        }
      }
    }
  }

  def waitUntilWatchIteration(
      logsObservable: Observable[(String, String)],
      totalIterations: Int,
      targetMsg: String
  ): Task[Unit] = {
    def count(ps: List[(String, String)]) = ps.count(_._2.contains(targetMsg))

    def waitForIterationFor(duration: FiniteDuration): Task[Unit] = {
      logsObservable
        .takeByTimespan(duration)
        .toListL
        .map(ps => assert(totalIterations == count(ps)))
    }

    waitForIterationFor(FiniteDuration(1500, "ms"))
      .onErrorFallbackTo(waitForIterationFor(FiniteDuration(3000, "ms")))
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
      val logger = new PublisherLogger(logObserver, debug = true, DebugFilter.All)

      val futureWatchedCompiledState =
        compiledState.withLogger(logger).compileHandle(`A`, watch = true)

      val HasIterationStoppedMsg = s"Watching ${numberDirsOf(compiledState.getDagFor(`A`))}"
      def waitUntilIteration(totalIterations: Int): Task[Unit] =
        waitUntilWatchIteration(logsObservable, totalIterations, HasIterationStoppedMsg)

      TestUtil.await(FiniteDuration(5, TimeUnit.SECONDS)) {
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
    import monix.reactive.Consumer

    import bloop.util.monix.FoldLeftAsyncConsumer
    val slowConsumer = FoldLeftAsyncConsumer.consume[Unit, Seq[String]](()) {
      case (_: Unit, msg: Seq[String]) =>
        Task {
          Thread.sleep(40)
          received
            .++=("Received ")
            .++=(msg.mkString(", "))
            .++=(System.lineSeparator)
          ()
        }
    }

    import bloop.util.monix.BloopBufferTimedObservable
    val a: Observable[Seq[String]] = new BloopBufferTimedObservable(observable, 40.millis, 0)
    import monix.reactive.OverflowStrategy

    val consumingTask =
      new BloopBufferTimedObservable(observable, 40.millis, 0)
      //observable
      //  .debounce(40.millis)
        .collect { case s if !s.isEmpty => s }
        //.whileBusyBuffer(OverflowStrategy.Unbounded)
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
