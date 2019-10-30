package bloop.dap

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import bloop.logging.RecordingLogger
import bloop.util.{TestProject, TestUtil}
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.DebugSessionParamsDataKind._

object DebugProtocolSpec extends DebugBspBaseSuite {
  test("starts a debug session") {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println("Hello, World!")
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        val output = state.withDebugSession(project, mainClassParams("Main")) { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            output <- client.allOutput
          } yield output
        }

        assertNoDiff(output, "Hello, World!\n")
      }
    }
  }

  // when the session detaches from the JVM, the JDI once again writes to the standard output
  test("restarted session does not contain JDI output") {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    Thread.sleep(10000)
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        val output = state.withDebugSession(project, mainClassParams("Main")) { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()

            previousSession <- client.restart()

            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.disconnect()

            previousSessionOutput <- previousSession.allOutput
          } yield previousSessionOutput
        }

        assertNoDiff(output, "")
      }
    }
  }

  test("picks up source changes across sessions") {
    val correctMain =
      """
        |object Main {
        |  def main(args: Array[String]): Unit = {
        |    println("Non-blocking Hello!")
        |  }
        |}
     """.stripMargin

    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println("Blocking Hello!")
           |    synchronized(wait())
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        // start debug session and the immediately disconnect from it
        val blockingSessionOutput = state.withDebugSession(project, mainClassParams("Main")) {
          client =>
            for {
              _ <- client.initialize()
              _ <- client.launch()
              _ <- client.configurationDone()
              output <- client.firstOutput
              _ <- client.disconnect()
            } yield output
        }

        assertNoDiff(blockingSessionOutput, "Blocking Hello!")

        // fix the main class
        val sources = state.toTestState.getProjectFor(project).sources
        val mainFile = sources.head.resolve("Main.scala")
        Files.write(mainFile.underlying, correctMain.getBytes(StandardCharsets.UTF_8))

        // start the next debug session
        val output = state.withDebugSession(project, mainClassParams("Main")) { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            output <- client.allOutput
          } yield output
        }

        assertNoDiff(output, "Non-blocking Hello!")
      }
    }
  }

  test("starts test suites") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-scalajs-1.0", workspace, logger) { build =>
        val project = build.projectFor("test-project-test")
        val testFilters = List(
          "hello.JUnitTest",
          "hello.ScalaCheckTest",
          "hello.ScalaTestTest",
          "hello.Specs2Test"
        )

        val output = build.state.withDebugSession(project, testSuiteParams(testFilters)) { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            output <- client.allOutput
          } yield output
        }

        testFilters.foreach { testSuite =>
          assert(output.contains(s"All tests in $testSuite passed"))
        }
      }
    }
  }

  def mainClassParams(mainClass: String): bsp.BuildTargetIdentifier => bsp.DebugSessionParams = {
    target =>
      val targets = List(target)
      val data = bsp.ScalaMainClass(mainClass, Nil, Nil)
      val json = bsp.ScalaMainClass.encodeScalaMainClass(data)
      bsp.DebugSessionParams(targets, ScalaMainClass, json)
  }

  def testSuiteParams(
      filters: List[String]
  ): bsp.BuildTargetIdentifier => bsp.DebugSessionParams = { target =>
    import io.circe.syntax._
    val targets = List(target)
    val json = filters.asJson
    bsp.DebugSessionParams(targets, ScalaTestSuites, json)
  }
}
