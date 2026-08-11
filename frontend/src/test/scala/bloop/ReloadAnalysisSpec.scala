package bloop

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

import bloop.cli.ExitStatus
import bloop.engine.ExecutionContext
import bloop.engine.State
import bloop.engine.caches.ResultsCache
import bloop.engine.tasks.compilation.CompileGatekeeper
import bloop.io.AbsolutePath
import bloop.io.ParallelOps
import bloop.io.ParallelOps.CopyMode
import bloop.logging.NoopLogger
import bloop.logging.RecordingLogger
import bloop.task.Task
import bloop.util.TestProject
import bloop.util.TestUtil

object ReloadAnalysisSpec extends bloop.testing.BaseSuite {

  object Sources {
    val `A.scala` =
      """/A.scala
        |class A
      """.stripMargin
    val `B.scala` =
      """/B.scala
        |class B extends A
      """.stripMargin
    val `B2.scala` =
      """/B.scala
        |class B extends A { def foo: Int = 1 }
      """.stripMargin
  }

  test("reload makes an externally restored analysis visible without a restart") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      val project = compiledState.getProjectFor(`A`)
      val analysisFile = project.analysisOut
      assert(analysisFile.exists)
      val lastSuccessful = compiledState.state.results.lastSuccessfulResult(project) match {
        case Some(result) => result
        case None => fail("Missing last successful result for 'a'")
      }

      // Snapshot the analysis and the classes directory it references, as an external
      // save/restore tool would do before handing the state back to a running server
      TestUtil.await(FiniteDuration(30, TimeUnit.SECONDS))(lastSuccessful.populatingProducts)
      val classesDir = lastSuccessful.classesDir
      val backupAnalysis = workspace.resolve("backup-analysis.bin")
      val backupClasses = workspace.resolve("backup-classes")
      Files.copy(
        analysisFile.underlying,
        backupAnalysis.underlying,
        StandardCopyOption.REPLACE_EXISTING
      )
      copyDirectory(classesDir, backupClasses)

      // Diverge the in-memory and persisted state from the snapshot with a semantic change
      assertIsFile(writeFile(`A`.srcFor("B.scala"), Sources.`B2.scala`))
      val secondCompiledState = compiledState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)
      assertNoDiff(
        logger.compilingInfos.mkString(System.lineSeparator),
        """Compiling a (2 Scala sources)
          |Compiling a (1 Scala source)
        """.stripMargin
      )

      // The non-noop compile schedules a background deletion of the superseded classes
      // directory; wait for it so that restoring the directory afterwards cannot race it
      waitUntilDeleted(classesDir)

      // Restore the snapshot on disk while the "server" (test state) is still running
      Files.copy(
        backupAnalysis.underlying,
        analysisFile.underlying,
        StandardCopyOption.REPLACE_EXISTING
      )
      copyDirectory(backupClasses, classesDir)
      assertIsFile(writeFile(`A`.srcFor("B.scala"), Sources.`B.scala`))

      val reloadedState = secondCompiledState.reloadAnalysis(`A`)
      assertExitStatus(reloadedState, ExitStatus.Ok)

      // The restored analysis matches the reverted sources, so compilation must be a no-op
      val finalState = reloadedState.compile(`A`)
      assertExitStatus(finalState, ExitStatus.Ok)
      assertSuccessfulCompilation(finalState, projects, isNoOp = true)
      assertNoDiff(
        logger.compilingInfos.mkString(System.lineSeparator),
        """Compiling a (2 Scala sources)
          |Compiling a (1 Scala source)
        """.stripMargin
      )
    }
  }

  test("reload drops in-memory results when the analysis file is missing") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      val analysisFile = compiledState.getProjectFor(`A`).analysisOut
      assert(analysisFile.exists)
      Files.delete(analysisFile.underlying)

      val reloadedState = compiledState.reloadAnalysis(`A`)
      assertExitStatus(reloadedState, ExitStatus.Ok)

      // Without the reload the in-memory analysis would make this compile a no-op
      val secondCompiledState = reloadedState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)
      assertSuccessfulCompilation(secondCompiledState, projects, isNoOp = false)
      assertNoDiff(
        logger.compilingInfos.mkString(System.lineSeparator),
        """Compiling a (2 Scala sources)
          |Compiling a (2 Scala sources)
        """.stripMargin
      )
    }
  }

  test("reload preserves the current state when the analysis file is corrupt") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      val analysisFile = compiledState.getProjectFor(`A`).analysisOut
      assert(analysisFile.exists)
      Files.write(analysisFile.underlying, "not a valid analysis file".getBytes())

      val reloadedState = compiledState.reloadAnalysis(`A`)
      assertExitStatus(reloadedState, ExitStatus.RunError)
      assert(logger.errors.exists(_.contains("could not be read")))

      // An unusable analysis must not cost the state the server already had
      val secondCompiledState = reloadedState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)
      assertSuccessfulCompilation(secondCompiledState, projects, isNoOp = true)
      assertNoDiff(
        logger.compilingInfos.mkString(System.lineSeparator),
        """Compiling a (2 Scala sources)
        """.stripMargin
      )
    }
  }

  test("reload rejects an analysis whose output directory belongs to another project") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`A.scala`))
      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`).compile(`B`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      // Restoring state that was persisted for a different project records an output directory
      // Bloop does not manage for this one, and following it would later delete a foreign path
      val analysisOfA = compiledState.getProjectFor(`A`).analysisOut
      val analysisOfB = compiledState.getProjectFor(`B`).analysisOut
      Files.copy(
        analysisOfB.underlying,
        analysisOfA.underlying,
        StandardCopyOption.REPLACE_EXISTING
      )

      val reloadedState = compiledState.reloadAnalysis(`A`)
      assertExitStatus(reloadedState, ExitStatus.RunError)
      assert(logger.errors.exists(_.contains("is not an internal classes directory")))

      val secondCompiledState = reloadedState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)
      assertSuccessfulCompilation(secondCompiledState, List(`A`), isNoOp = true)
    }
  }

  test("reload fails when the restored analysis references a missing classes directory") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      val project = compiledState.getProjectFor(`A`)
      val analysisFile = project.analysisOut
      val lastSuccessful = compiledState.state.results.lastSuccessfulResult(project) match {
        case Some(result) => result
        case None => fail("Missing last successful result for 'a'")
      }
      TestUtil.await(FiniteDuration(30, TimeUnit.SECONDS))(lastSuccessful.populatingProducts)
      val backupAnalysis = workspace.resolve("backup-analysis.bin")
      Files.copy(
        analysisFile.underlying,
        backupAnalysis.underlying,
        StandardCopyOption.REPLACE_EXISTING
      )

      assertIsFile(writeFile(`A`.srcFor("B.scala"), Sources.`B2.scala`))
      val secondCompiledState = compiledState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)

      // Restore the analysis but not the classes directory it points to, which the non-noop
      // compile above schedules for background deletion
      waitUntilDeleted(lastSuccessful.classesDir)
      Files.copy(
        backupAnalysis.underlying,
        analysisFile.underlying,
        StandardCopyOption.REPLACE_EXISTING
      )
      val reloadedState = secondCompiledState.reloadAnalysis(`A`)
      assertExitStatus(reloadedState, ExitStatus.RunError)
      assert(logger.errors.exists(_.contains("no longer exists")))

      // A restore that left the products behind must be reported rather than silently
      // costing a rebuild, and the state the server had must survive it
      val finalState = reloadedState.compile(`A`)
      assertExitStatus(finalState, ExitStatus.Ok)
      assertSuccessfulCompilation(finalState, projects, isNoOp = true)
      assertNoDiff(
        logger.compilingInfos.mkString(System.lineSeparator),
        """Compiling a (2 Scala sources)
          |Compiling a (1 Scala source)
        """.stripMargin
      )
    }
  }

  test("reload is refused while a clean is removing the state it would replace") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      val project = compiledState.getProjectFor(`A`)
      val cleanedState = CompileGatekeeper.cleaning(List(project)) {
        Task {
          val reloadedState = compiledState.reloadAnalysis(`A`)
          assertExitStatus(reloadedState, ExitStatus.RunError)
          assert(logger.errors.exists(_.contains("while projects are cleaned")))
        }
      }
      TestUtil.await(FiniteDuration(30, TimeUnit.SECONDS))(cleanedState)

      // The reservation is released once the clean finishes, so reloads work again
      val reloadedState = compiledState.reloadAnalysis(`A`)
      assertExitStatus(reloadedState, ExitStatus.Ok)
    }
  }

  test("reload is refused while a compilation is in flight") {
    TestUtil.withinWorkspace { workspace =>
      object SlowSources {
        val `Macros.scala` =
          """/Macros.scala
            |package macros
            |
            |import scala.reflect.macros.blackbox.Context
            |import scala.language.experimental.macros
            |
            |object SleepMacro {
            |  def sleep(): Unit = macro sleepImpl
            |  def sleepImpl(c: Context)(): c.Expr[Unit] = {
            |    import c.universe._
            |    Thread.sleep(2000)
            |    reify { () }
            |  }
            |}""".stripMargin

        val `User.scala` =
          """/User.scala
            |object User {
            |  macros.SleepMacro.sleep()
            |}
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `Macros` = TestProject(workspace, "macros", List(SlowSources.`Macros.scala`))
      val `User` = TestProject(workspace, "user", List(SlowSources.`User.scala`), List(`Macros`))
      val projects = List(`Macros`, `User`)
      val state = loadState(workspace, projects, logger)
      val compiledMacros = state.compile(`Macros`)
      assertExitStatus(compiledMacros, ExitStatus.Ok)

      val runningCompilation = compiledMacros.compileHandle(`User`)
      val deadline = System.currentTimeMillis() + 20000
      while (
        !logger.compilingInfos.exists(_.contains("Compiling user")) &&
        System.currentTimeMillis() < deadline
      ) Thread.sleep(50)
      assert(logger.compilingInfos.exists(_.contains("Compiling user")))

      val reloadedState = compiledMacros.reloadAnalysis()
      assertExitStatus(reloadedState, ExitStatus.RunError)
      assert(logger.errors.exists(_.contains("Cannot reload compilation state")))

      val compiledUser = Await.result(runningCompilation, FiniteDuration(60, TimeUnit.SECONDS))
      assertExitStatus(compiledUser, ExitStatus.Ok)
    }
  }

  test("a client holding older state cannot reinstate state a reload removed") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      val analysisFile = compiledState.getProjectFor(`A`).analysisOut
      Files.delete(analysisFile.underlying)
      val reloadedState = compiledState.reloadAnalysis(`A`)
      assertExitStatus(reloadedState, ExitStatus.Ok)

      // `compiledState` predates the reload and still carries the removed result. Compiling from
      // it must not bring that result back, so the compile has to rebuild from scratch
      val staleCompiledState = compiledState.compile(`A`)
      assertExitStatus(staleCompiledState, ExitStatus.Ok)
      assertSuccessfulCompilation(staleCompiledState, projects, isNoOp = false)
      assertNoDiff(
        logger.compilingInfos.mkString(System.lineSeparator),
        """Compiling a (2 Scala sources)
          |Compiling a (2 Scala sources)
        """.stripMargin
      )
    }
  }

  test("imported state is used by a client that has not compiled anything yet") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      val project = compiledState.getProjectFor(`A`)
      val analysisFile = project.analysisOut
      val lastSuccessful = compiledState.state.results.lastSuccessfulResult(project) match {
        case Some(result) => result
        case None => fail("Missing last successful result for 'a'")
      }
      TestUtil.await(FiniteDuration(30, TimeUnit.SECONDS))(lastSuccessful.populatingProducts)
      val backupAnalysis = workspace.resolve("backup-analysis.bin")
      Files.copy(
        analysisFile.underlying,
        backupAnalysis.underlying,
        StandardCopyOption.REPLACE_EXISTING
      )

      // A state loaded without an analysis has no compilation result of its own, which is the
      // position a server is in when a cache is restored into it before it has compiled
      Files.delete(analysisFile.underlying)
      val freshLogger = new RecordingLogger(ansiCodesSupported = false)
      val freshState = loadState(workspace, projects, freshLogger)
      Files.copy(
        backupAnalysis.underlying,
        analysisFile.underlying,
        StandardCopyOption.REPLACE_EXISTING
      )

      val reloadedState = freshState.reloadAnalysis(`A`)
      assertExitStatus(reloadedState, ExitStatus.Ok)

      // Compiling from the state that predates the import must still use what was imported
      val compiledFromStale = freshState.compile(`A`)
      assertExitStatus(compiledFromStale, ExitStatus.Ok)
      assertSuccessfulCompilation(compiledFromStale, projects, isNoOp = true)
      assertNoDiff(freshLogger.compilingInfos.mkString(System.lineSeparator), "")
    }
  }

  test("an imported result survives a request that started before the reload") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`A.scala`))
      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`).compile(`B`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      val projectA = compiledState.getProjectFor(`A`)
      CompileGatekeeper.commitState(state.state, compiledState.state)

      // A request that acted on B only, holding the results from before A was reloaded
      val staleRequest = compiledState.state

      Files.delete(projectA.analysisOut.underlying)
      assertExitStatus(compiledState.reloadAnalysis(`A`), ExitStatus.Ok)
      assert(cachedResults(staleRequest).lastSuccessfulResult(projectA).isEmpty)

      // Publishing a change of its own must not carry the result it still holds for A along
      // with it. B is only here so that the commit has something to publish
      val projectB = compiledState.getProjectFor(`B`)
      val requestResult = staleRequest.copy(
        results = staleRequest.results.replaceWithReloaded(Map(projectB -> None))
      )
      CompileGatekeeper.commitState(staleRequest, requestResult)

      assert(cachedResults(staleRequest).lastSuccessfulResult(projectA).isEmpty)
    }
  }

  test("reload fails when a clean removes the state it read, even over an earlier removal") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      val project = compiledState.getProjectFor(`A`)
      // Leave a removal behind, so a second one writes the same marker the reload would compare
      CompileGatekeeper.clearSuccessfulResult(project)

      val reloaded = CompileGatekeeper.reloadSuccessfulResults(List(project)) { () =>
        // Another removal lands while the state is being read from disk
        CompileGatekeeper.clearSuccessfulResult(project)
        ResultsCache.loadPersistedResults(List(project), workspace, logger)
      }(_ => ())

      reloaded match {
        case Left(failure) => assert(failure.reason == "concurrent-change")
        case Right(_) => fail("Reload reported success although the state it read was removed")
      }
    }
  }

  test("a delayed commit cannot reverse a reload of the same project") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      val project = compiledState.getProjectFor(`A`)
      CompileGatekeeper.commitState(state.state, compiledState.state)

      // A request that ran before the reload and is about to publish its own result for A
      val delayedRequest = compiledState.state

      val analysisFile = project.analysisOut
      Files.delete(analysisFile.underlying)
      val reloadedState = compiledState.reloadAnalysis(`A`)
      assertExitStatus(reloadedState, ExitStatus.Ok)

      // Committing it must adopt the state the reload left behind, not reinstate its own
      CompileGatekeeper.commitState(state.state, delayedRequest)
      assert(cachedResults(delayedRequest).lastSuccessfulResult(project).isEmpty)
    }
  }

  private def cachedResults(state: State) = {
    State.stateCache
      .getStateFor(
        state.build.origin,
        state.client,
        state.pool,
        state.commonOptions,
        state.logger
      )
      .map(_.results)
      .getOrElse(fail("Build is not cached"))
  }

  private def waitUntilDeleted(dir: AbsolutePath): Unit = {
    val deadline = System.currentTimeMillis() + 30000
    while (dir.exists && System.currentTimeMillis() < deadline) Thread.sleep(50)
    assert(!dir.exists)
  }

  private def copyDirectory(from: AbsolutePath, to: AbsolutePath): Unit = {
    val config = ParallelOps.CopyConfiguration(2, CopyMode.ReplaceExisting, Set.empty, Set.empty)
    val copyTask = ParallelOps.copyDirectories(config)(
      from.underlying,
      to.underlying,
      ExecutionContext.ioScheduler,
      enableCancellation = false,
      NoopLogger
    )
    TestUtil.await(FiniteDuration(30, TimeUnit.SECONDS))(copyTask)
    ()
  }
}
