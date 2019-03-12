package bloop.bsp

import bloop.cli.{ExitStatus, BspProtocol}
import bloop.util.{TestUtil, TestProject}
import bloop.logging.{RecordingLogger, BspClientLogger}
import bloop.internal.build.BuildInfo

import monix.eval.Task
import monix.execution.{Scheduler, ExecutionModel}

import scala.concurrent.duration.FiniteDuration

object TcpBspConnectionSpec extends BspConnectionSpec(BspProtocol.Tcp)
object LocalBspConnectionSpec extends BspConnectionSpec(BspProtocol.Local)

class BspConnectionSpec(override val protocol: BspProtocol) extends BspBaseSuite {
  // A custom pool we use to make sure we don't block on threads
  val poolFor6Clients: Scheduler = Scheduler(
    java.util.concurrent.Executors.newFixedThreadPool(20),
    ExecutionModel.Default
  )

  test("initialize several clients concurrently and simulate a hard disconnection") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(workspace, "a", Nil)
      val projects = List(`A`)

      def createClient(logger: RecordingLogger): Task[Unit] = {
        Task {
          val logger = new RecordingLogger(ansiCodesSupported = false)
          val bspLogger = new BspClientLogger(logger)
          val configDir = TestProject.populateWorkspace(workspace, projects)
          val bspCommand = createBspCommand(configDir)
          val state = TestUtil.loadTestProject(configDir.underlying, logger)
          // Run the clients on our own unbounded IO scheduler to allow client concurrency
          val scheduler = Some(poolFor6Clients)
          val bspState = openBspConnection(
            state,
            bspCommand,
            configDir,
            bspLogger,
            userScheduler = scheduler
          )
          // Note we open an unmanaged state and we exit without sending `shutdown`/`exit`
          assert(bspState.status == ExitStatus.Ok)
          checkConnectionIsInitialized(logger)
        }
      }

      val logger1 = new RecordingLogger(ansiCodesSupported = false)
      val logger2 = new RecordingLogger(ansiCodesSupported = false)
      val logger3 = new RecordingLogger(ansiCodesSupported = false)
      val logger4 = new RecordingLogger(ansiCodesSupported = false)
      val logger5 = new RecordingLogger(ansiCodesSupported = false)
      val logger6 = new RecordingLogger(ansiCodesSupported = false)

      val client1 = createClient(logger1)
      val client2 = createClient(logger2)
      val client3 = createClient(logger3)
      val client4 = createClient(logger4)
      val client5 = createClient(logger5)
      val client6 = createClient(logger6)

      val firstBatchOfClients = List(client1, client2, client3, client4, client5, client6)
      TestUtil.await(FiniteDuration(5, "s"), poolFor6Clients) {
        Task.gatherUnordered(firstBatchOfClients).map(_ => ())
      }

      val logger7 = new RecordingLogger(ansiCodesSupported = false)
      val logger8 = new RecordingLogger(ansiCodesSupported = false)
      val logger9 = new RecordingLogger(ansiCodesSupported = false)
      val logger10 = new RecordingLogger(ansiCodesSupported = false)

      val client7 = createClient(logger7)
      val client8 = createClient(logger8)
      val client9 = createClient(logger9)
      val client10 = createClient(logger10)

      val secondBatchOfClients = List(client7)//, client8, client9, client10)
      TestUtil.await(FiniteDuration(10, "s"), poolFor6Clients) {
        Task.gatherUnordered(secondBatchOfClients).map(_ => ())
      }
    }
  }

  def checkConnectionIsInitialized(logger: RecordingLogger): Unit = {
    val jsonrpc = logger.debugs.filter(_.startsWith(" -->"))
    // Filter out the initialize request that contains platform-specific details
    val allButInitializeRequest = jsonrpc.filterNot(_.contains("""build/initialize""""))
    assertNoDiff(
      allButInitializeRequest.mkString(System.lineSeparator),
      s"""| --> {
          |  "result" : {
          |    "displayName" : "${BuildInfo.bloopName}",
          |    "version" : "${BuildInfo.version}",
          |    "bspVersion" : "${BuildInfo.bspVersion}",
          |    "capabilities" : {
          |      "compileProvider" : {
          |        "languageIds" : [
          |          "scala",
          |          "java"
          |        ]
          |      },
          |      "testProvider" : {
          |        "languageIds" : [
          |          "scala",
          |          "java"
          |        ]
          |      },
          |      "runProvider" : {
          |        "languageIds" : [
          |          "scala",
          |          "java"
          |        ]
          |      },
          |      "inverseSourcesProvider" : true,
          |      "dependencySourcesProvider" : true,
          |      "resourcesProvider" : false,
          |      "buildTargetChangedProvider" : false
          |    },
          |    "data" : null
          |  },
          |  "id" : "2",
          |  "jsonrpc" : "2.0"
          |}
          | --> {
          |  "method" : "build/initialized",
          |  "params" : {
          |    
          |  },
          |  "jsonrpc" : "2.0"
          |}""".stripMargin
    )
  }
}
