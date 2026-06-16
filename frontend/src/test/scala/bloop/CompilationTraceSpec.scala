package bloop

import bloop.util.TestUtil
import bloop.logging.RecordingLogger
import bloop.data.WorkspaceSettings
import bloop.data.TraceSettings
import bloop.tracing.CompilationTrace
import java.nio.file.Files
import bloop.cli.ExitStatus
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray

object CompilationTraceSpec extends BaseCompileSpec {
  override protected val TestProject = util.TestProject

  test("compilation trace is created when enabled") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false, debug = false)
      val `A` = TestProject(workspace, "a", sources)
      val projects = List(`A`)

      // Write workspace settings with compilationTrace enabled
      val settings = WorkspaceSettings(
        None,
        None,
        None,
        None,
        Some(TraceSettings(None, None, None, None, None, None, None, Some(true))),
        None
      )
      val configDir = workspace.resolve(".bloop")
      if (!Files.exists(configDir.underlying)) Files.createDirectories(configDir.underlying)
      WorkspaceSettings.writeToFile(configDir, settings, logger)
      Files.createFile(workspace.resolve("compilation-trace.json").underlying)

      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      val traceFile = workspace.resolve("compilation-trace-a.json")
      assert(Files.exists(traceFile.underlying))

      val bytes = Files.readAllBytes(traceFile.underlying)
      val trace = readFromArray[CompilationTrace](bytes)(CompilationTrace.codec)

      assert(trace.project == "a")
      assert(trace.files.exists(_.endsWith("Foo.scala")))
      assert(trace.diagnostics.isEmpty)
      assert(!trace.isNoOp)
    }
  }

  test("compilation trace contains diagnostics") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo {
          |  def bar: Int = "string"
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false, debug = false)
      val `A` = TestProject(workspace, "a", sources)
      val projects = List(`A`)

      val settings = WorkspaceSettings(
        None,
        None,
        None,
        None,
        Some(TraceSettings(None, None, None, None, None, None, None, Some(true))),
        None
      )
      val configDir = workspace.resolve(".bloop")
      if (!Files.exists(configDir.underlying)) Files.createDirectories(configDir.underlying)
      WorkspaceSettings.writeToFile(configDir, settings, logger)
      Files.createFile(workspace.resolve("compilation-trace.json").underlying)

      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.CompilationError)

      val traceFile = workspace.resolve("compilation-trace-a.json")
      assert(Files.exists(traceFile.underlying))

      val bytes = Files.readAllBytes(traceFile.underlying)
      val trace = readFromArray[CompilationTrace](bytes)(CompilationTrace.codec)

      assert(trace.diagnostics.nonEmpty)
      assert(trace.diagnostics.exists(_.message.contains("type mismatch")))
    }
  }

  test("compilation trace records no-op") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false, debug = false)
      val `A` = TestProject(workspace, "a", sources)
      val projects = List(`A`)

      val settings = WorkspaceSettings(
        None,
        None,
        None,
        None,
        Some(TraceSettings(None, None, None, None, None, None, None, Some(true))),
        None
      )
      val configDir = workspace.resolve(".bloop")
      if (!Files.exists(configDir.underlying)) Files.createDirectories(configDir.underlying)
      WorkspaceSettings.writeToFile(configDir, settings, logger)
      Files.createFile(workspace.resolve("compilation-trace.json").underlying)

      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)

      val secondCompiledState = compiledState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)

      val traceFile = workspace.resolve("compilation-trace-a.json")
      val bytes = Files.readAllBytes(traceFile.underlying)
      val trace = readFromArray[CompilationTrace](bytes)(CompilationTrace.codec)

      // Since we overwrite, it should be the trace of the last compilation (no-op)
      assert(trace.isNoOp)
    }
  }
}
