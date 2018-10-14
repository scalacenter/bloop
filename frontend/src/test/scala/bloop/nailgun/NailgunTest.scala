package bloop.nailgun

import java.io.PrintStream

import org.junit.Assert.{assertEquals, assertNotEquals}
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import bloop.Server
import bloop.bsp.BspServer
import bloop.logging.{LogContext, ProcessLogger, RecordingLogger}
import bloop.tasks.TestUtil
import com.martiansoftware.nailgun.NGServer
import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}
import org.apache.commons.io.IOUtils

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

/**
 * Base class for writing test for the nailgun integration.
 */
abstract class NailgunTest {

  private final val TEST_PORT = 8998

  private final val nailgunPool = Scheduler.computation(parallelism = 2)

  /**
   * Starts a Nailgun server, creates a client and executes operations with that client.
   * The server is shut down at the end of `op`.
   *
   * @param log  The logger that will receive all produced output.
   * @param config The config directory in which the client will be.
   * @param op   A function that will receive the instantiated Client.
   * @return The result of executing `op` on the client.
   */
  def withServerTask[T](log: RecordingLogger, config: Path)(
      op: (RecordingLogger, Client) => T): Task[T] = {
    val oldIn = System.in
    val oldOut = System.out
    val oldErr = System.err
    val inStream = IOUtils.toInputStream("")
    val outStream = new PrintStream(ProcessLogger.toOutputStream(log.serverInfo))
    val errStream = new PrintStream(ProcessLogger.toOutputStream(log.serverError))

    val serverIsStarted = scala.concurrent.Promise[Unit]()
    val serverIsFinished = scala.concurrent.Promise[Unit]()
    val serverLogic = Task {
      var optServer: Option[NGServer] = None

      // Trick nailgun into thinking these are the real streams
      System.setIn(inStream)
      System.setOut(outStream)
      System.setErr(errStream)
      try {
        optServer = Some(Server.instantiateServer(Array(TEST_PORT.toString)))
      } finally {
        System.setIn(oldIn)
        System.setOut(oldOut)
        System.setErr(oldErr)
      }

      val server = optServer.getOrElse(sys.error("The nailgun server failed to initialize!"))
      serverIsStarted.success(())
      server.run()
      serverIsFinished.success(())
    }

    val client = new Client(TEST_PORT, log, config)
    val clientCancel = Task {
      /* Exit on Windows seems to return a failing exit code (but no logs are logged).
       * This suggests that the nailgun 'exit' method isn't Windows friendly somehow, but
       * for the sake of development I'm merging this since this method will be rarely called. */
      if (BspServer.isWindows) {
        val exitStatusCode = client.issue("exit")
        log.debug(s"The status code for exit in Windows was ${exitStatusCode}.")
      } else client.success("exit")

      outStream.flush()
      errStream.flush()
    }

    val clientLogic = Task(op(log, client)).doOnFinish(_ => clientCancel).doOnCancel(clientCancel)
    val startTrigger = Task.fromFuture(serverIsStarted.future)
    val endTrigger = Task.fromFuture(serverIsFinished.future)
    val runClient = startTrigger.flatMap(_ => clientLogic.flatMap(t => endTrigger.map(_ => t)))

    // These tests can be flaky on Windows, so if they fail we restart them up to 3 times
    Task
      .zip2(serverLogic, runClient)
      .map(t => t._2)
      .timeout(FiniteDuration(40, TimeUnit.SECONDS))
  }

  /**
   * Starts a Nailgun server, creates a client and executes operations with that client.
   * The server is shut down at the end of `op`.
   *
   * @param log  The logger that will receive all produced output.
   * @param config The config directory in which the client will be.
   * @param op   A function that will receive the instantiated Client.
   * @return The result of executing `op` on the client.
   */
  def withServer[T](config: Path, log: => RecordingLogger)(
      op: (RecordingLogger, Client) => T): T = {
    // These tests can be flaky on Windows, so if they fail we restart them up to 3 times
    val f = withServerTask(log, config)(op)
    // Note we cannot use restart because our task uses promises that cannot be completed twice
      .onErrorFallbackTo(
        withServerTask(log, config)(op).onErrorFallbackTo(withServerTask(log, config)(op)))
      .runAsync(nailgunPool)
    try Await.result(f, FiniteDuration(125, TimeUnit.SECONDS))
    finally f.cancel()
  }

  /**
   * Starts a server and provides a client in the directory of project `name`.
   * A logger that will receive all output will be created and passed to `op`.
   *
   * @param name The name of the project where the client will be.
   * @param op   A function that accepts a logger and a client.
   * @return The result of executing `op` on the logger and client.
   */
  def withServerInProject[T](name: String)(op: (RecordingLogger, Client) => T): T = {
    withServer(TestUtil.getBloopConfigDir(name), new RecordingLogger())(op)
  }

  /**
   * A client that interacts with a running Nailgun server.
   *
   * @param port The port on which the client should communicate with the server.
   * @param log recording logger for test run.
   * @param config The base directory with the configuration files.
   */
  private case class Client(port: Int, log: RecordingLogger, config: Path) {
    private val base = TestUtil.getBaseFromConfigDir(config)
    private val configPath = config.toAbsolutePath.toString
    private val clientPath = bloop.internal.build.BuildInfo.nailgunClientLocation.getAbsolutePath

    assert(Files.exists(base), s"Base directory doesn't exist: '$base'.")
    assert(Files.exists(Paths.get(clientPath)), s"Couldn't find Nailgun client at '$clientPath'.")

    private def processBuilder(cmd: Seq[String]): ProcessBuilder = {
      val cmdBase =
        if (BspServer.isWindows) "python" :: clientPath.toString :: Nil
        else clientPath.toString :: Nil
      val builder = new ProcessBuilder((cmdBase ++ (s"--nailgun-port=$port" +: cmd)): _*)
      builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
      val env = builder.environment()
      env.put("BLOOP_OWNER", "owner")
      builder.directory(base.toFile)
    }

    /**
     * Execute a command `cmd` on the server and return the exit code.
     *
     * @param cmd The command to execute
     * @return The exit code of the operation.
     */
    def issue(cmd: String*): Int = {
      issueAsProcess(cmd: _*).waitFor()
    }

    /**
     * Execute a command `cmd` on the server and return the process
     * executing the specified command.
     *
     * @param cmd0 The command to execute
     * @return The exit code of the operation.
     */
    def issueAsProcess(cmd0: String*): Process = {
      val cmd =
        if (cmd0.contains("--config-dir"))
          cmd0
        else
          cmd0 ++ List("--config-dir", configPath)

      val builder = processBuilder(cmd)
      val process = builder.start()
      val processLogger = new ProcessLogger(log, process)
      processLogger.start()
      process
    }

    /**
     * Executes a command `cmd` expecting a 0 exit code.
     *
     * @param cmd The command to run.
     */
    def success(cmd: String*): Unit = {
      val failMessage: String =
        s"""Success expected, but command failed. Logs were:
           |${log.getMessages.mkString("\n")}
           |""".stripMargin
      assertEquals(failMessage, 0, issue(cmd: _*).toLong)
    }

    /**
     * Executes a command `cmd`, expecting a non-zero exit code.
     *
     * @param cmd The command to run.
     */
    def fail(cmd: String*): Unit = {
      val failMessage: String =
        s"""Failure expected, but command succeeded. Logs were:
           |${log.getMessages.mkString("\n")}
           |""".stripMargin
      assertNotEquals(failMessage, 0, issue(cmd: _*).toLong)
    }
  }
}
