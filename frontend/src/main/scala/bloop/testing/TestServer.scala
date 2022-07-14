package bloop.testing

import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ServerSocket

import scala.concurrent.Promise
import scala.util.Try
import scala.util.control.NonFatal

import ch.epfl.scala.debugadapter.testing.TestSuiteEvent

import bloop.cli.CommonOptions
import bloop.config.Config
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.task.Task

import sbt.ForkConfiguration
import sbt.ForkTags
import sbt.testing.Event
import sbt.testing.Framework
import sbt.testing.TaskDef

/**
 * Implements the protocol that the forked remote JVM talks with the host process.
 *
 * This protocol is not formal and has been implemented after sbt's `ForkTests`.
 */
final class TestServer(
    logger: Logger,
    eventHandler: BloopTestSuiteEventHandler,
    classLoader: ClassLoader,
    discoveredTests: Map[Framework, List[TaskDef]],
    args: List[Config.TestArgument],
    opts: CommonOptions
) {

  private implicit val logContext: DebugFilter = DebugFilter.Test

  private val server = new ServerSocket(0)
  private val (runners, tasks) = {
    def getRunner(framework: Framework) = {
      val frameworkClass = framework.getClass.getName
      val fargs = args.filter(_.framework.forall(_.names.contains(frameworkClass)))
      frameworkClass -> TestInternals.getRunner(framework, fargs, classLoader)
    }
    // Return frameworks and tasks in order to ensure a deterministic test execution
    val sorted = discoveredTests.toList.sortBy(_._1.name())
    (sorted.map(_._1).map(getRunner), sorted.flatMap(_._2.sortBy(_.fullyQualifiedName())))
  }

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
      try {
        os.writeObject(config)
        val taskDefs = tasks.map(forkFingerprint)
        os.writeObject(taskDefs.toArray)
        os.writeInt(runners.size)
        taskDefs.foreach { taskDef =>
          taskDef.fingerprint()
        }

        val taskDefsDescription = taskDefs.map { taskDef =>
          val selectors = taskDef.selectors().toList.map(_.toString()).mkString("(", ",", ")")
          s"${taskDef.fullyQualifiedName()}$selectors"
        }
        logger.debug(s"Sent task defs to test server: $taskDefsDescription")

        runners.foreach {
          case (frameworkClass, runner) =>
            logger.debug(s"Sending runner to test server: ${frameworkClass} ${runner.args.toList}")
            os.writeObject(Array(frameworkClass))
            os.writeObject(runner.args)
            os.writeObject(runner.remoteArgs)
        }

        os.flush()
        receiveLogs(is, os)
      } catch {
        case NonFatal(t) =>
          logger.error(s"Failed to initialize communication: ${t.getMessage}")
          logger.trace(t)
      }
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
      def cleanSocketResources() = Task {
        if (!alreadyClosed) {
          for {
            _ <- Try(is.close())
            _ <- Try(os.close())
            _ <- Try(socket.close())
          } yield {
            alreadyClosed = false
          }
          ()
        }
      }

      Task(talk(is, os, config))
        .doOnFinish(_ => cleanSocketResources())
        .doOnCancel(cleanSocketResources())
    }

    def closeServer(t: Option[Throwable], fromCancel: Boolean) = Task {
      t.foreach {
        case NonFatal(e) =>
          logger.error(s"Unexpected error during remote test execution: '${e.getMessage}'.")
          logger.trace(e)
        case _ =>
      }

      runners.foreach(_._2.done())

      server.close()
      // Do both just in case the logger streams have been closed by nailgun
      if (fromCancel) {
        opts.ngout.println("The test execution was successfully cancelled.")
        logger.debug("Test server has been successfully cancelled.")
      } else {
        opts.ngout.println("The test execution was successfully closed.")
        logger.debug("Test server has been successfully closed.")
      }
    }

    val listener = testListeningTask
      .doOnCancel(closeServer(None, true))
      .doOnFinish(closeServer(_, false))

    TestOrchestrator(Task.fromFuture(serverStarted.future), listener)
  }
}
