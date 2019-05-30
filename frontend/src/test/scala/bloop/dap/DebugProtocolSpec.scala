package bloop.dap
import bloop.bsp.BspBaseSuite
import bloop.cli.BspProtocol
import bloop.logging.RecordingLogger
import bloop.util.{TestProject, TestUtil}

object DebugProtocolSpec extends BspBaseSuite {
  override val protocol: BspProtocol = BspProtocol.Local

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
        val output = state.withDebugSession(project, "Main") { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            _ <- client.disconnect()
            output <- client.output
          } yield output
        }

        assertNoDiff(output, "Hello, World!\n")
      }
    }
  }

  test("restarted session does not contain additional output") {
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
        val output = state.withDebugSession(project, "Main") { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()

            previousSession <- client.restart()

            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.terminated
            _ <- client.disconnect()

            previousSessionOutput <- previousSession.output

          } yield previousSessionOutput
        }

        assertNoDiff(output, "Hello, World!\n")
      }
    }
  }

}
