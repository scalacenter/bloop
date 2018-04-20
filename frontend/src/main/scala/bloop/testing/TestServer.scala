package bloop.testing

import java.io.{ObjectInputStream, ObjectOutputStream}
import java.net.ServerSocket

import bloop.cli.CommonOptions
import bloop.config.Config

import scala.util.control.NonFatal
import bloop.logging.Logger
import monix.eval.Task
import sbt.{ForkConfiguration, ForkTags}
import sbt.testing.{Event, EventHandler, TaskDef}

import scala.concurrent.Promise

/**
 * Implements the protocol that the forked remote JVM talks with the host process.
 *
 * This protocol is not formal and has been implemented after sbt's `ForkTests`.
 */
final class TestServer(
    logger: Logger,
    eventHandler: EventHandler,
    discoveredTests: DiscoveredTests,
    args: List[Config.TestArgument],
    opts: CommonOptions
) {

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
        case ForkTags.`Done` =>
          os.writeObject(ForkTags.Done)
          os.flush()
        case Array(ForkTags.`Error`, s: String) =>
          logger.error(s)
          receiveLogs(is, os)
        case Array(ForkTags.`Warn`, s: String) =>
          logger.warn(s)
          receiveLogs(is, os)
        case Array(ForkTags.`Info`, s: String) =>
          logger.info(s)
          receiveLogs(is, os)
        case Array(ForkTags.`Debug`, s: String) =>
          logger.debug(s)
          receiveLogs(is, os)
        case t: Throwable =>
          logger.trace(t)
          receiveLogs(is, os)
        case Array(_: String, tEvents: Array[Event]) =>
          tEvents.foreach(eventHandler.handle)
          receiveLogs(is, os)
      }
    }

    def talk(is: ObjectInputStream, os: ObjectOutputStream, config: ForkConfiguration): Unit = {
      os.writeObject(config)
      val taskDefs = tasks.map(forkFingerprint)
      os.writeObject(taskDefs.toArray)
      os.writeInt(frameworks.size)

      frameworks.foreach { framework =>
        val frameworkClass = framework.getClass.getName()
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
      logger.debug(s"Firing up test server at $port. Waiting for client...")
      serverStarted.trySuccess(())
      server.accept()
    }

    val testListeningTask = clientConnection.flatMap { socket =>
      logger.debug("Test server established connection with remote JVM.")
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
      logger.debug("Test server has been successfully closed.")
    }

    val listener = testListeningTask
      .doOnCancel(closeServer(None, true))
      .doOnFinish(closeServer(_, false))

    TestOrchestrator(Task.fromFuture(serverStarted.future), listener)
  }
}
