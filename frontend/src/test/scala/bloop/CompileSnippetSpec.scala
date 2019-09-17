package bloop

import java.nio.charset.StandardCharsets

import bloop.config.Config
import bloop.util.TestUtil
import bloop.util.TestProject
import bloop.engine.tasks.CompileSnippetTask
import bloop.logging.RecordingLogger
import scala.concurrent.duration.FiniteDuration

object CompileSnippetSpec extends bloop.testing.BaseSuite {
  val scalaJars = TestUtil.scalaInstance.allJars.map(_.toPath).toList
  ignore("test compile snippet works") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val state = loadState(workspace, Nil, logger)
      val snippet = "package a; class A"
      val classesDir = workspace.resolve("hello").underlying
      val classpath = scalaJars
      val scala =
        Config.Scala("org.scala-lang", "scala-compiler", "2.12.9", Nil, scalaJars, None, None)
      val config = Config.SnippetConfig(classesDir, classpath, scala)
      val compileTask = CompileSnippetTask.compileSnippet(
        snippet.getBytes(StandardCharsets.UTF_8),
        config,
        state.state,
        logger
      )

      def compile = TestUtil.await(FiniteDuration(5, "s")) {
        compileTask
      }

      for (i <- 0 to 5000) {
        val start = System.currentTimeMillis()
        val result = compile
        val end = System.currentTimeMillis()
        pprint.log(end - start)
        //pprint.log(result)
      }
    }
  }

  ""
  test("test compile snippet works II") {
    TestUtil.withinWorkspace { workspace =>
      def compile(snippet: String): Compiler.Result = {
        val logger = new RecordingLogger(ansiCodesSupported = false)
        val state = loadState(workspace, Nil, logger)
        val classesDir = workspace.resolve("hello").underlying
        val classpath = scalaJars
        val scala =
          Config.Scala("org.scala-lang", "scala-compiler", "2.12.9", Nil, scalaJars, None, None)
        val config = Config.SnippetConfig(classesDir, classpath, scala)
        val compileTask = CompileSnippetTask.compileSnippet(
          snippet.getBytes(StandardCharsets.UTF_8),
          config,
          state.state,
          logger
        )
        TestUtil.await(FiniteDuration(5, "s"))(compileTask)
      }

      compile("package a; class A")
      compile("package b; class B extends A")
    }
  }
}
