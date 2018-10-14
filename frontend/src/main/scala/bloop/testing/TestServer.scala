package bloop.testing

import java.io.{ObjectInputStream, ObjectOutputStream}
import java.net.ServerSocket

import bloop.cli.CommonOptions
import bloop.config.Config

import scala.util.control.NonFatal
import bloop.logging.{LogContext, Logger}
import monix.eval.Task
import sbt.{ForkConfiguration, ForkTags}
import sbt.testing.{Event, TaskDef}

import scala.concurrent.Promise

/**
 * Implements the protocol that the forked remote JVM talks with the host process.
 *
 * This protocol is not formal and has been implemented after sbt's `ForkTests`.
 */
final class TestServer(
    logger: Logger,
    eventHandler: TestSuiteEventHandler,
    discoveredTests: DiscoveredTests,
    args: List[Config.TestArgument],
    opts: CommonOptions
) {

  private implicit val logContext: LogContext = LogContext.Test

  private val server = new ServerSocket(0)
  private val frameworks = discoveredTests.tests.keys
  private val tasks = discoveredTests.tests.values.flatten

  case class TestOrchestrator(startServer: Task[Unit], reporter: Task[Unit])
  val port = server.getLocalPort
  def listenToTests: TestOrchestrator = {
    def forkFingerprint(td: TaskDef): TaskDef = {
      val newFingerprint = sbt.SerializableFingerprints.forkFingerprint(td.fingerprint)
      new TaskDef(td.fullyQualifiedName, newFingerprint, td.explicitlySpecified, td.selectors)
    }

    @annotation.tailrec
    def receiveLogs(is: ObjectInputStream, os: ObjectOutputStream): Unit = {
      is.readObject() match {
        case Array(ForkTags.`Error`, s: String) =>
          eventHandler.handle(TestSuiteEvent.Error(s))
          receiveLogs(is, os)
        case Array(ForkTags.`Warn`, s: String) =>
          eventHandler.handle(TestSuiteEvent.Warn(s))
          receiveLogs(is, os)
        case Array(ForkTags.`Info`, s: String) =>
          eventHandler.handle(TestSuiteEvent.Info(s))
          receiveLogs(is, os)
        case Array(ForkTags.`Debug`, s: String) =>
          eventHandler.handle(TestSuiteEvent.Debug(s))
          receiveLogs(is, os)
        case t: Throwable =>
          eventHandler.handle(TestSuiteEvent.Trace(t))
          receiveLogs(is, os)
        case Array(testSuite: String, events: Array[Event]) =>
          eventHandler.handle(TestSuiteEvent.Results(testSuite, events.toList))
          receiveLogs(is, os)
        case ForkTags.`Done` =>
          eventHandler.handle(TestSuiteEvent.Done)
          os.writeObject(ForkTags.Done)
          os.flush()
      }
    }

    def talk(is: ObjectInputStream, os: ObjectOutputStream, config: ForkConfiguration): Unit = {
      os.writeObject(config)
      val taskDefs = tasks.map(forkFingerprint)
      os.writeObject(taskDefs.toArray)
      os.writeInt(frameworks.size)

      frameworks.foreach { framework =>
        val frameworkClass = framework.getClass.getName
        val fargs = args.filter { arg =>
          arg.framework match {
            case Some(f) => f.names.contains(frameworkClass)
            case None => true
          }
        }

        val runner = TestInternals.getRunner(framework, fargs, discoveredTests.classLoader)
        os.writeObject(Array(framework.getClass.getCanonicalName))
        os.writeObject(runner.args)
        os.writeObject(runner.remoteArgs)
      }

      os.flush()
      receiveLogs(is, os)
    }

    val serverStarted = Promise[Unit]()
    val clientConnection = Task {
      logger.debugInContext(s"Firing up test server at $port. Waiting for client...")
      serverStarted.trySuccess(())
      server.accept()
    }

    val testListeningTask = clientConnection.flatMap { socket =>
      logger.debugInContext("Test server established connection with remote JVM.")
      val os = new ObjectOutputStream(socket.getOutputStream)
      os.flush()
      val is = new ObjectInputStream(socket.getInputStream)
      val config = new ForkConfiguration(logger.ansiCodesSupported, /* parallel = */ false)

      @volatile var alreadyClosed: Boolean = false
      val cleanSocketResources = Task {
        if (!alreadyClosed) {
          is.close()
          os.close()
          socket.close()
          alreadyClosed = true
        }
      }

      Task(talk(is, os, config))
        .doOnFinish(_ => cleanSocketResources)
        .doOnCancel(cleanSocketResources)
    }

    def closeServer(t: Option[Throwable], fromCancel: Boolean) = Task {
      t.foreach {
        case NonFatal(e) =>
          logger.error(s"Unexpected error during remote test execution: '${e.getMessage}'.")
          logger.trace(e)
        case _ =>
      }

      server.close()
      // Do both just in case the logger streams have been closed by nailgun
      opts.ngout.println("The test execution was successfully cancelled.")
      logger.debugInContext("Test server has been successfully closed.")
    }

    val listener = testListeningTask
      .doOnCancel(closeServer(None, true))
      .doOnFinish(closeServer(_, false))

    TestOrchestrator(Task.fromFuture(serverStarted.future), listener)
  }
}
