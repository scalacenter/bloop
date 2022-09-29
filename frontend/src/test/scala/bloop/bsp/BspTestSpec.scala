package bloop.bsp

import bloop.cli.BspProtocol
import bloop.cli.ExitStatus
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil
import bloop.internal.build.BuildTestInfo
import bloop.ScalaInstance

object TcpBspTestSpec extends BspTestSpec(BspProtocol.Tcp)
object LocalBspTestSpec extends BspTestSpec(BspProtocol.Local)

class BspTestSpec(override val protocol: BspProtocol) extends BspBaseSuite {

  val junitJars = BuildTestInfo.junitTestJars.map(AbsolutePath.apply)
  private val scalaVersion = "2.12.15"

  test("bsp test succeeds") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/FooTest.scala
          |class FooTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(true)
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val compilerJars = ScalaInstance
        .resolve("org.scala-lang", "scala-compiler", scalaVersion, logger)
        .allJars
        .map(AbsolutePath.apply)

      val A =
        TestProject(workspace, "a", sources, enableTests = true, jars = compilerJars ++ junitJars)
      loadBspState(workspace, List(A), logger) { state =>
        val compiled = state.compile(A)
        val testResult = compiled.toTestState.test(A)
        assertExitStatus(testResult, ExitStatus.Ok)
      }
    }
  }

  test("bsp test fails") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/FooTest.scala
          |class FooTest {
          |  @org.junit.Test def foo(): Unit = {
          |    org.junit.Assert.fail()
          |  }
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val compilerJars = ScalaInstance
        .resolve("org.scala-lang", "scala-compiler", scalaVersion, logger)
        .allJars
        .map(AbsolutePath.apply)

      val A =
        TestProject(workspace, "a", sources, enableTests = true, jars = compilerJars ++ junitJars)
      loadBspState(workspace, List(A), logger) { state =>
        val compiled = state.compile(A)
        val testResult = compiled.toTestState.test(A)
        assertExitStatus(testResult, ExitStatus.TestExecutionError)
      }
    }
  }
}
