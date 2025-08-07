package bloop

import java.nio.file.Files

import bloop.data.WorkspaceSettings
import bloop.logging.RecordingLogger
import bloop.util.TestUtil

object CompilationTraceSpec extends bloop.testing.BaseSuite {
  
  test("compilation trace is created when enabled") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |object Foo {
          |  val x = 1
          |  def main(args: Array[String]): Unit = {
          |    println("Hello World")
          |  }
          |}
        """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = util.TestProject(workspace, "a", sources)
      val projects = List(`A`)
      
      // Create workspace settings with compilation trace enabled
      val workspaceSettings = WorkspaceSettings(
        javaSemanticDBVersion = None,
        semanticDBVersion = None,
        supportedScalaVersions = None,
        refreshProjectsCommand = None,
        traceSettings = None,
        enableBestEffortMode = None,
        enableCompilationTrace = Some(true)
      )
      
      val state = loadState(workspace, projects, logger, Some(workspaceSettings))
      val compiledState = state.compile(`A`)
      
      assertExitStatus(compiledState, cli.ExitStatus.Ok)
      
      // Check that the compilation trace file was created
      val traceFile = workspace.resolve("compilation.trace.json")
      assert(Files.exists(traceFile.underlying), s"Compilation trace file should exist at $traceFile")
      
      // Check that the trace file contains expected content
      val traceContent = new String(Files.readAllBytes(traceFile.underlying))
      assert(traceContent.contains("\"project\""), "Trace should contain project information")
      assert(traceContent.contains("\"compiledFiles\""), "Trace should contain compiled files")
      assert(traceContent.contains("\"result\""), "Trace should contain compilation result")
    }
  }
  
  test("compilation trace is not created when disabled") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Bar.scala
          |object Bar {
          |  val y = 2
          |}
        """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `B` = util.TestProject(workspace, "b", sources)
      val projects = List(`B`)
      
      // Create workspace settings with compilation trace disabled (default)
      val workspaceSettings = WorkspaceSettings(
        javaSemanticDBVersion = None,
        semanticDBVersion = None,
        supportedScalaVersions = None,
        refreshProjectsCommand = None,
        traceSettings = None,
        enableBestEffortMode = None,
        enableCompilationTrace = Some(false)
      )
      
      val state = loadState(workspace, projects, logger, Some(workspaceSettings))
      val compiledState = state.compile(`B`)
      
      assertExitStatus(compiledState, cli.ExitStatus.Ok)
      
      // Check that the compilation trace file was NOT created
      val traceFile = workspace.resolve("compilation.trace.json")
      assert(!Files.exists(traceFile.underlying), s"Compilation trace file should not exist at $traceFile")
    }
  }
}