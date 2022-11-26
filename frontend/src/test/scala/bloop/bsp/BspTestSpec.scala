package bloop.bsp

import bloop.cli.BspProtocol
import bloop.cli.ExitStatus
import bloop.internal.build.BuildTestInfo
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil

object TcpBspTestSpec extends BspTestSpec(BspProtocol.Tcp)
object LocalBspTestSpec extends BspTestSpec(BspProtocol.Local)

class BspTestSpec(override val protocol: BspProtocol) extends BspBaseSuite {

  val junitJars = BuildTestInfo.junitTestJars.map(AbsolutePath.apply).toArray

  test("bsp test succeeds") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/FooTest.scala
          |class FooTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(true)
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val A = TestProject(workspace, "a", sources, enableTests = true, jars = junitJars)
      loadBspState(workspace, List(A), logger) { state =>
        val testResult: ManagedBspTestState = state.test(A)
        assertExitStatus(testResult, ExitStatus.Ok)
      }
    }
  }

  test("bsp test fails") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/FooTest.scala
          |class FooTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.fail()
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val A = TestProject(workspace, "a", sources, enableTests = true, jars = junitJars)
      loadBspState(workspace, List(A), logger) { state =>
        val testResult: ManagedBspTestState = state.test(A)
        assertExitStatus(testResult, ExitStatus.TestExecutionError)
      }
    }
  }
}
