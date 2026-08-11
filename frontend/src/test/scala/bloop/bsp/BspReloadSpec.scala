package bloop.bsp

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

import bloop.cli.BspProtocol
import bloop.cli.ExitStatus
import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import bloop.io.ParallelOps
import bloop.io.ParallelOps.CopyMode
import bloop.logging.NoopLogger
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil

object TcpBspReloadSpec extends BspReloadSpec(BspProtocol.Tcp)
object LocalBspReloadSpec extends BspReloadSpec(BspProtocol.Local)

abstract class BspReloadSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {

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

  test("bloop/reloadAnalysis makes an externally restored analysis visible") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      val projects = List(`A`)
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`)
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)

        val project = compiledState.toTestState.getProjectFor(`A`)
        val analysisFile = project.analysisOut
        assert(analysisFile.exists)
        val lastSuccessful = compiledState.toTestState.state.results
          .lastSuccessfulResult(project) match {
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

        // Diverge the in-memory and persisted state from the snapshot
        writeFile(`A`.srcFor("B.scala"), Sources.`B2.scala`)
        val secondCompiledState = compiledState.compile(`A`)
        assertExitStatus(secondCompiledState, ExitStatus.Ok)

        // The non-noop compile schedules a background deletion of the superseded classes
        // directory; wait for it so that restoring the directory afterwards cannot race it
        waitUntilDeleted(classesDir)

        // Restore the snapshot on disk and reload while the server is running
        Files.copy(
          backupAnalysis.underlying,
          analysisFile.underlying,
          StandardCopyOption.REPLACE_EXISTING
        )
        copyDirectory(backupClasses, classesDir)
        writeFile(`A`.srcFor("B.scala"), Sources.`B.scala`)

        val reloadedState = secondCompiledState.reloadAnalysis(`A`)

        // The restored analysis matches the reverted sources, so this compile is a no-op
        val finalState = reloadedState.compile(`A`)
        assertExitStatus(finalState, ExitStatus.Ok)
        assertNoDiff(finalState.lastDiagnostics(`A`), "")
      }
    }
  }

  test("compile diagnostics are still reset after an analysis reload") {
    TestUtil.withinWorkspace { workspace =>
      object FailingSources {
        val `Foo.scala` =
          """/Foo.scala
            |object Foo {
            |  val x: String = 1
            |}
          """.stripMargin
        val `Foo2.scala` =
          """/Foo.scala
            |object Foo {
            |  val x: String = "1"
            |}
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(FailingSources.`Foo.scala`))
      val projects = List(`A`)
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`)
        assertExitStatus(compiledState, ExitStatus.CompilationError)
        assertNoDiff(
          compiledState.lastDiagnostics(`A`),
          """#1: task start 1
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#1: a/src/Foo.scala
            |  -> List(Diagnostic(Range(Position(1,18),Position(1,18)),Some(Error),Some(_),Some(_),type mismatch;  found   : Int(1)  required: String,None,None,Some({"actions":[]})))
            |  -> reset = true
            |#1: task finish 1
            |  -> errors 1, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )

        val reloadedState = compiledState.reloadAnalysis(`A`)

        // The per-client failed-compilation state must survive the reload so that the next
        // successful compile still resets the previously published diagnostics
        writeFile(`A`.srcFor("Foo.scala"), FailingSources.`Foo2.scala`)
        val secondCompiledState = reloadedState.compile(`A`)
        assertExitStatus(secondCompiledState, ExitStatus.Ok)
        assertNoDiff(
          secondCompiledState.lastDiagnostics(`A`),
          """#2: task start 2
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#2: a/src/Foo.scala
            |  -> List()
            |  -> reset = true
            |#2: task finish 2
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )
      }
    }
  }

  test("workspace/reload does not import compilation state") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      val projects = List(`A`)
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`)
        assertExitStatus(compiledState, ExitStatus.Ok)

        val project = compiledState.toTestState.getProjectFor(`A`)
        val analysisFile = project.analysisOut
        val lastSuccessful = compiledState.toTestState.state.results
          .lastSuccessfulResult(project) match {
          case Some(result) => result
          case None => fail("Missing last successful result for 'a'")
        }
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

        writeFile(`A`.srcFor("B.scala"), Sources.`B2.scala`)
        val secondCompiledState = compiledState.compile(`A`)
        assertExitStatus(secondCompiledState, ExitStatus.Ok)
        waitUntilDeleted(classesDir)

        Files.copy(
          backupAnalysis.underlying,
          analysisFile.underlying,
          StandardCopyOption.REPLACE_EXISTING
        )
        copyDirectory(backupClasses, classesDir)
        writeFile(`A`.srcFor("B.scala"), Sources.`B.scala`)

        // Reloading the build configuration must leave compilation state alone, so the
        // restored analysis stays invisible until it is imported explicitly
        secondCompiledState.workspaceReload()

        val thirdCompiledState = secondCompiledState.compile(`A`)
        assertExitStatus(thirdCompiledState, ExitStatus.Ok)
        assertNoDiff(
          thirdCompiledState.lastDiagnostics(`A`),
          """#3: task start 3
            |  -> Msg: Compiling a (1 Scala source)
            |  -> Data kind: compile-task
            |#3: task finish 3
            |  -> errors 0, warnings 0, noop false
            |  -> Msg: Compiled 'a'
            |  -> Data kind: compile-report
            """.stripMargin
        )
      }
    }
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
