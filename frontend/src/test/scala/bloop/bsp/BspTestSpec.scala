package bloop.bsp

import ch.epfl.scala.bsp.ScalaTestClassesItem
import ch.epfl.scala.bsp.ScalaTestParams
import ch.epfl.scala.bsp.ScalaTestSuiteSelection
import ch.epfl.scala.bsp.ScalaTestSuites

import bloop.cli.BspProtocol
import bloop.cli.ExitStatus
import bloop.internal.build.BuildTestInfo
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil

import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import jsonrpc4s.RawJson
import scalaz.std.java.time

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

  test("Test only when datakind is scala-test succeeds in testing only one test suite") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/FooTest.scala
          |class FooTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(false)
          |}
          """.stripMargin,
        """/BarTest.scala
          |class BarTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(true)
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val A = TestProject(workspace, "a", sources, enableTests = true, jars = junitJars)
      loadBspState(workspace, List(A), logger) { state =>
        val cls =
          state.testClasses(A)

        val classes =
          Some(
            List(
              ScalaTestClassesItem(cls.items.head.target, cls.items.head.framework, List("BarTest"))
            )
          )

        val data = RawJson(writeToArray(ScalaTestParams(classes, None)))

        val testResult: ManagedBspTestState =
          state.test(A, dataKind = Some("scala-test"), data = Some(data))
        assertExitStatus(testResult, ExitStatus.Ok)
      }
    }
  }

  test("Test only when datakind is scala-test fails in testing only one test suite") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/FooTest.scala
          |class FooTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(true)
          |}
          """.stripMargin,
        """/BarTest.scala
          |class BarTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(false)
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val A = TestProject(workspace, "a", sources, enableTests = true, jars = junitJars)
      loadBspState(workspace, List(A), logger) { state =>
        val cls =
          state.testClasses(A)

        val classes =
          Some(
            List(
              ScalaTestClassesItem(cls.items.head.target, cls.items.head.framework, List("BarTest"))
            )
          )

        val data = RawJson(writeToArray(ScalaTestParams(classes, None)))

        val testResult: ManagedBspTestState =
          state.test(A, dataKind = Some("scala-test"), data = Some(data))
        assertExitStatus(testResult, ExitStatus.TestExecutionError)
      }
    }
  }

  test("Test only when datakind is scala-test-suites succeeds in testing only one test suite") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/FooTest.scala
          |class FooTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(false)
          |}
          """.stripMargin,
        """/BarTest.scala
          |class BarTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(true)
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val A = TestProject(workspace, "a", sources, enableTests = true, jars = junitJars)
      loadBspState(workspace, List(A), logger) { state =>
        val classes = writeToArray(List("BarTest"))(JsonCodecMaker.make[List[String]])
        val data = RawJson(classes)

        val testResult: ManagedBspTestState =
          state.test(A, dataKind = Some("scala-test-suites"), data = Some(data))
        assertExitStatus(testResult, ExitStatus.Ok)
      }
    }
  }

  test("Test only when datakind is scala-test-suites fails in testing only one test suite") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/FooTest.scala
          |class FooTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(true)
          |}
          """.stripMargin,
        """/BarTest.scala
          |class BarTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(false)
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val A = TestProject(workspace, "a", sources, enableTests = true, jars = junitJars)
      loadBspState(workspace, List(A), logger) { state =>
        val classes = writeToArray(List("BarTest"))(JsonCodecMaker.make[List[String]])
        val data = RawJson(classes)

        val testResult: ManagedBspTestState =
          state.test(A, dataKind = Some("scala-test-suites"), data = Some(data))
        assertExitStatus(testResult, ExitStatus.TestExecutionError)
      }
    }
  }

  test(
    "Test only when datakind is scala-test-suites-selection succeeds in testing only one test suite"
  ) {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/FooTest.scala
          |class FooTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(false)
          |}
          """.stripMargin,
        """/BarTest.scala
          |class BarTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(true)
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val A = TestProject(workspace, "a", sources, enableTests = true, jars = junitJars)

      loadBspState(workspace, List(A), logger) { state =>
        val testSuites = ScalaTestSuites(
          List(ScalaTestSuiteSelection("BarTest", Nil)),
          Nil,
          Nil
        )
        val data = RawJson(writeToArray(testSuites))

        val testResult: ManagedBspTestState =
          state.test(A, dataKind = Some("scala-test-suites-selection"), data = Some(data))
        assertExitStatus(testResult, ExitStatus.Ok)
      }
    }
  }

  test(
    "Test only when datakind is scala-test-suites-selection fails in testing only one test suite"
  ) {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/FooTest.scala
          |class FooTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(true)
          |}
          """.stripMargin,
        """/BarTest.scala
          |class BarTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(false)
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val A = TestProject(workspace, "a", sources, enableTests = true, jars = junitJars)

      loadBspState(workspace, List(A), logger) { state =>
        val testSuites = ScalaTestSuites(
          List(ScalaTestSuiteSelection("BarTest", Nil)),
          Nil,
          Nil
        )
        val data = RawJson(writeToArray(testSuites))

        val testResult: ManagedBspTestState =
          state.test(A, dataKind = Some("scala-test-suites-selection"), data = Some(data))
        assertExitStatus(testResult, ExitStatus.TestExecutionError)
      }
    }
  }

  test(
    "Test only when datakind is scala-test-suites-selection succeeds in testing only one test suite with specific test"
  ) {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/FooTest.scala
          |class FooTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(false)
          |}
          """.stripMargin,
        """/BarTest.scala
          |class BarTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(false)
          |  @org.junit.Test def bar(): Unit = org.junit.Assert.assertTrue(true)
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val A = TestProject(workspace, "a", sources, enableTests = true, jars = junitJars)

      loadBspState(workspace, List(A), logger) { state =>
        val testSuites = ScalaTestSuites(
          List(ScalaTestSuiteSelection("BarTest", List("bar"))),
          Nil,
          Nil
        )
        val data = RawJson(writeToArray(testSuites))

        val testResult: ManagedBspTestState =
          state.test(A, dataKind = Some("scala-test-suites-selection"), data = Some(data))
        assertExitStatus(testResult, ExitStatus.Ok)
      }
    }
  }

  test(
    "Test only when datakind is scala-test-suites-selection fails in testing only one test suite with specific test"
  ) {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/FooTest.scala
          |class FooTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(false)
          |}
          """.stripMargin,
        """/BarTest.scala
          |class BarTest {
          |  @org.junit.Test def foo(): Unit = org.junit.Assert.assertTrue(true)
          |  @org.junit.Test def bar(): Unit = org.junit.Assert.assertTrue(false)
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val A = TestProject(workspace, "a", sources, enableTests = true, jars = junitJars)

      loadBspState(workspace, List(A), logger) { state =>
        val testSuites = ScalaTestSuites(
          List(ScalaTestSuiteSelection("BarTest", List("bar"))),
          Nil,
          Nil
        )
        val data = RawJson(writeToArray(testSuites))

        val testResult: ManagedBspTestState =
          state.test(A, dataKind = Some("scala-test-suites-selection"), data = Some(data))
        assertExitStatus(testResult, ExitStatus.TestExecutionError)
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

  test("Test TestNG running a test suite") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/FooTest.scala
          |import org.testng.annotations.Test
          |import org.testng.Assert.assertEquals
          |class FooTest {
          |  @Test
          |  def aaa() = assertEquals(1, 2)
          |}
          """.stripMargin,
        """/BarTest.scala
          |import org.testng.annotations.Test
          |import org.testng.Assert.assertEquals
          |class BarTest {
          |  @Test
          |  def aaa() = assertEquals(1, 1)
          |}
          """.stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val A = TestProject(
        workspace,
        "a",
        sources,
        enableTests = true,
        jars = TestUtil.getTestNGDep(logger)
      )
      loadBspState(workspace, List(A), logger) { state =>
        val cls =
          state.testClasses(A)

        val classes =
          Some(
            List(
              ScalaTestClassesItem(cls.items.head.target, cls.items.head.framework, List("BarTest"))
            )
          )

        val data = RawJson(writeToArray(ScalaTestParams(classes, None)))

        val testResult: ManagedBspTestState =
          state.test(A, dataKind = Some("scala-test"), data = Some(data), timeout = 10)
        assertExitStatus(testResult, ExitStatus.Ok)
      }
    }
  }
}
