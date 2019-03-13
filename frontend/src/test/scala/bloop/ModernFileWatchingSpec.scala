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

object ModernFileWatchingSpec extends BaseSuite {
  test("simulate an incremental compiler session") {
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

        val `C2.scala` =
          """/C.scala
            |object C2 extends A with B
          """.stripMargin
      }

      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`B.scala`))
      val `C` = TestProject(workspace, "c", List(Sources.`C.scala`), List(`A`, `B`))
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
      def waitUntilWatchIteration(totalIterations: Int) =
        logsObservable
          .takeByTimespan(FiniteDuration(1, "s"))
          .toListL
          .map(ps => assert(totalIterations == ps.count(_._2.contains(HasIterationStoppedMsg))))

      //val missingFile = Files.createFile(`C`.baseDir.resolve("E.scala"))
      TestUtil.await(FiniteDuration(10, TimeUnit.SECONDS)) {
        for {
          _ <- waitUntilWatchIteration(1)
          _ <- Task(writeFile(`C`.srcFor("C.scala"), Sources.`C2.scala`))
          _ <- waitUntilWatchIteration(2)
          _ <- Task(writeFile(`C`.baseDir.resolve("E.scala"), Sources.`C.scala`))
          _ <- waitUntilWatchIteration(2)
        } yield ()
      }

      logger.dump()

    /*
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, List(`A`, `B`))
      assertNoDiff(
        logger.compilingInfos.sorted.mkString(System.lineSeparator),
        """Compiling a (2 Scala sources)
          |Compiling b (1 Scala source)""".stripMargin
      )
     */

    //assertIsFile(writeFile(`A`.srcFor("A.scala"), Sources.`A2.scala`))
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
}
