package bloop.bsp

import bloop.cli.BspProtocol
import bloop.cli.ExitStatus
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil

object TcpBspTestSpec extends BspTestSpec(BspProtocol.Tcp)
object LocalBspTestSpec extends BspTestSpec(BspProtocol.Local)

class BspTestSpec(override val protocol: BspProtocol) extends BspBaseSuite {

  val junitJars = System
    .getProperty("java.class.path")
    .split(java.io.File.pathSeparator)
    .collect { case path if path.contains("junit") => AbsolutePath(path) }

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

      val A = TestProject(workspace, "a", sources, jars = junitJars)
      loadBspState(workspace, List(A), logger) { state =>
        val testResult: ManagedBspTestState = state.test(A)
        assertExitStatus(testResult, ExitStatus.Ok)
      }
    }
  }

  test("bsp test fails") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/FooTest.scala
          |class FooTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.fail()
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val A = TestProject(workspace, "a", sources, jars = junitJars)
      loadBspState(workspace, List(A), logger) { state =>
        val testResult: ManagedBspTestState = state.test(A)
        assertExitStatus(testResult, ExitStatus.TestExecutionError)
      }
    }
  }
}
