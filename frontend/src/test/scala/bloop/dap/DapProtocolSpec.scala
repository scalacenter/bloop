package bloop.dap
import bloop.bsp.BspBaseSuite
import bloop.cli.BspProtocol
import bloop.logging.RecordingLogger
import bloop.util.{TestProject, TestUtil}
import monix.eval.Task

object DapProtocolSpec extends BspBaseSuite {
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
            output <- client.output()
          } yield output
        }

        assertNoDiff(output, "Hello, World!")
      }
    }
  }

  test("restarts a debug session") {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println("Hello, World!")
           |    Thread.sleep(1000)
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
            _ <- client.output("Hello, World!\n")
            _ <- client.restart()
            _ <- client.exited
            _ <- client.terminated
            _ <- client.disconnect()
            output <- client.output()
          } yield output
        }

        assertNoDiff(output, "Hello, World!\nHello, World!\n")
      }
    }
  }
}
