package bloop.testing

import java.io.{ObjectInputStream, ObjectOutputStream, Serializable}
import java.net.{ServerSocket, SocketException}

import scala.util.control.NonFatal

import bloop.integrations.Argument
import bloop.logging.Logger

import sbt.{ForkConfiguration, ForkMain, ForkTags}
import sbt.testing.{
  AnnotatedFingerprint,
  Event,
  EventHandler,
  Fingerprint,
  Runner,
  SubclassFingerprint,
  TaskDef
}

/**
 * A server that communicates with the test agent in a forked JVM to run the tests.
 * Heavily inspired from sbt's `ForkTests.scala`.
 */
class TestServer(logger: Logger,
                 eventHandler: EventHandler,
                 discoveredTests: DiscoveredTests,
                 testArguments: Array[Argument]) {

  private val server = new ServerSocket(0)
  private val listener = new Thread(() => run())
  private val frameworks = discoveredTests.tests.keys
  private val tasks = discoveredTests.tests.values.flatten
  private val testLoader = discoveredTests.classLoader

  /** The port on which this server is listening. */
  val port = server.getLocalPort

  def whileRunning[T](op: => T): T = {
    start()
    try {
      val result = op
      listener.join()
      result
    } finally stop()
  }

  private[this] def start(): Unit = {
    logger.debug(s"Starting test server on port $port.")
    listener.start()
  }

  private[this] def stop(): Unit = {
    logger.debug("Terminating test server.")
    server.close()
  }

  private def run(): Unit = {
    logger.debug("Waiting for connection from remote JVM.")
    val socket = {
      try server.accept()
      catch {
        case ex: SocketException =>
          logger.error("Connection with remote JVM failed.")
          logger.trace(ex)
          server.close()
          return
      }
    }
    logger.debug("Remote JVM connected.")

    val os = new ObjectOutputStream(socket.getOutputStream)
    // Must flush the header that the constructor writes, otherwise the ObjectInputStream on the
    // other end may block indefinitely
    os.flush()
    val is = new ObjectInputStream(socket.getInputStream)

    try {
      val config = new ForkConfiguration(logger.ansiCodesSupported, /* parallel = */ false)
      os.writeObject(config)

      val taskDefs = tasks.map(forkFingerprint)
      os.writeObject(taskDefs.toArray)

      os.writeInt(frameworks.size)
      frameworks.foreach { framework =>
        val frameworkClass = framework.getClass
        val frameworkArguments = testArguments.filter(_.matches(frameworkClass))
        val runner = TestInternals.getRunner(framework, frameworkArguments, testLoader)
        os.writeObject(Array(framework.getClass.getCanonicalName))
        os.writeObject(runner.args)
        os.writeObject(runner.remoteArgs)
      }
      os.flush()

      new React(is, os, logger, eventHandler).react()
    } catch {
      case NonFatal(e) =>
        logger.error("An error occurred during remote test execution.")
        logger.trace(e)
    } finally {
      is.close()
      os.close()
      socket.close()
    }

  }

  private[this] def forkFingerprint(td: TaskDef): TaskDef = {
    val newFingerprint = sbt.SerializableFingerprints.forkFingerprint(td.fingerprint)
    new TaskDef(td.fullyQualifiedName, newFingerprint, td.explicitlySpecified, td.selectors)
  }

}

/**
 * Reacts on messages from the forked JVM.
 * Copied straight from ForkTests in sbt/sbt.
 *
 * Copyright 2011 - 2017, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under BSD-3-Clause license
 */
private final class React(is: ObjectInputStream,
                          os: ObjectOutputStream,
                          logger: Logger,
                          eventHandler: EventHandler) {
  import ForkTags._
  @annotation.tailrec
  def react(): Unit = {
    is.readObject() match {
      case `Done` =>
        os.writeObject(Done)
        os.flush()
      case Array(`Error`, s: String) =>
        logger.error(s)
        react()
      case Array(`Warn`, s: String) =>
        logger.warn(s)
        react()
      case Array(`Info`, s: String) =>
        logger.info(s)
        react()
      case Array(`Debug`, s: String) =>
        logger.debug(s)
        react()
      case t: Throwable =>
        logger.trace(t)
        react()
      case Array(_: String, tEvents: Array[Event]) =>
        tEvents.foreach(eventHandler.handle)
        react()
    }
  }
}
