package bloop.nailgun

import java.io.PrintStream

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{ExecutionException, TimeUnit}

import bloop.Server
import bloop.bsp.BspServer
import bloop.testing.BaseSuite
import bloop.logging.{DebugFilter, ProcessLogger, RecordingLogger}
import bloop.util.{TestUtil, CrossPlatform}

import com.martiansoftware.nailgun.{BloopThreadLocalInputStream, NGServer, ThreadLocalPrintStream}

import monix.eval.Task
import monix.execution.misc.NonFatal
import monix.execution.Scheduler

import org.apache.commons.io.IOUtils

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import java.nio.charset.StandardCharsets

/**
 * Base class for writing test for the nailgun integration.
 */
trait NailgunTestUtils {
  this: BaseSuite =>

  protected final val TEST_PORT = 8996
  private final val nailgunPool = Scheduler.computation(parallelism = 2)

  /**
   * Starts a Nailgun server, creates a client and executes operations with that client.
   * The server is shut down at the end of `op`.
   *
   * @param log  The logger that will receive all produced output.
   * @param config The config directory in which the client will be.
   * @param noExit Don't exit, the client is responsibleor exiting the server.
   * @param op   A function that will receive the instantiated Client.
   * @return The result of executing `op` on the client.
   */
  def withServerTask[T](log: RecordingLogger, config: Path, noExit: Boolean)(
      op: (RecordingLogger, Client) => T
  ): Task[T] = {
    implicit val ctx: DebugFilter = DebugFilter.All

    val currentIn = System.in
    val currentOut = System.out
    val currentErr = System.err

    val in = IOUtils.toInputStream("", StandardCharsets.UTF_8)
    val out = new PrintStream(ProcessLogger.toOutputStream(log.serverInfo))
    val err = new PrintStream(ProcessLogger.toOutputStream(log.serverError))
    val localIn = new BloopThreadLocalInputStream(in)
    val localOut = new ThreadLocalPrintStream(out)
    val localErr = new ThreadLocalPrintStream(err)
    localIn.init(in)
    localOut.init(out)
    localErr.init(err)
    System.in.synchronized {
      System.setIn(localIn)
      System.setOut(localOut)
      System.setErr(localErr)
    }

    val serverIsStarted = scala.concurrent.Promise[Unit]()
    val serverIsFinished = scala.concurrent.Promise[Unit]()
    val serverLogic = Task {
      // Trick nailgun into thinking these are the real streams
      import java.net.InetAddress
      val addr = InetAddress.getLoopbackAddress
      import monix.execution.misc.NonFatal
      try {
        val server = Server.launchServer(localIn, localOut, localErr, addr, TEST_PORT, log)
        serverIsStarted.success(())
        server.run()
        serverIsFinished.success(())
      } catch {
        case NonFatal(t) =>
          currentErr.println("Error when starting server")
          t.printStackTrace(currentErr)
          serverIsStarted.failure(t)
          serverIsFinished.failure(t)
      } finally {
        out.flush()
        err.flush()
      }
    }

    val client = new Client(TEST_PORT, log, config)
    def clientCancel(t: Option[Throwable]) = Task {
      out.flush()
      err.flush()

      t.foreach(t => log.trace(t))
      if (!noExit) {
        /* Exit on Windows seems to return a failing exit code (but no logs are logged).
         * This suggests that the nailgun 'exit' method isn't Windows friendly somehow, but
         * for the sake of development I'm merging this since this method will be rarely called. */
        if (CrossPlatform.isWindows) {
          val exitStatusCode = client.issue("exit")
          log.debug(s"The status code for exit in Windows was ${exitStatusCode}.")
        } else client.expectSuccess("exit")
      }

      System.in.synchronized {
        System.setIn(currentIn)
        System.setOut(currentOut)
        System.setErr(currentErr)
      }
    }

    val clientLogic =
      Task(op(log, client)).doOnFinish(clientCancel(_)).doOnCancel(clientCancel(None))
    val startTrigger = Task.fromFuture(serverIsStarted.future)
    val endTrigger = Task.fromFuture(serverIsFinished.future)
    val runClient = {
      for {
        _ <- startTrigger
        value <- clientLogic
        _ <- endTrigger
      } yield value
    }

    Task
      .zip2(serverLogic, runClient)
      .map(t => t._2)
      .timeout(FiniteDuration(35, TimeUnit.SECONDS))
  }

  /**
   * Starts a Nailgun server, creates a client and executes operations with that client.
   * The server is shut down at the end of `op`.
   *
   * @param log  The logger that will receive all produced output.
   * @param config The config directory in which the client will be.
   * @param noExit Don't exit, the client is responsible for exiting the server.
   * @param op   A function that will receive the instantiated Client.
   * @return The result of executing `op` on the client.
   */
  def withServer[T](config: Path, noExit: Boolean, log: => RecordingLogger)(
      op: (RecordingLogger, Client) => T
  ): T = {
    // These tests can be flaky on Windows, so if they fail we restart them up to 3 times
    val f = withServerTask(log, config, noExit)(op).runAsync(nailgunPool)
    // Note we cannot use restart because our task uses promises that cannot be completed twice
    try Await.result(f, FiniteDuration(35, TimeUnit.SECONDS))
    catch {
      case e: ExecutionException => throw e.getCause()
      case t: Throwable => throw t
    } finally f.cancel()
  }

  /**
   * A client that interacts with a running Nailgun server.
   *
   * @param port The port on which the client should communicate with the server.
   * @param log recording logger for test run.
   * @param config The base directory with the configuration files.
   */
  protected case class Client(port: Int, log: RecordingLogger, config: Path) {
    private val base = TestUtil.getBaseFromConfigDir(config)
    private val configPath = config.toAbsolutePath.toString
    private val clientPath = bloop.internal.build.BuildInfo.nailgunClientLocation.getAbsolutePath

    if (!Files.exists(base)) {
      fail(s"Base directory doesn't exist: '$base'.")
    }
    if (!Files.exists(Paths.get(clientPath))) {
      fail(s"Couldn't find Nailgun client at '$clientPath'.")
    }

    private def processBuilder(cmd: Seq[String]): ProcessBuilder = {
      val cmdBase =
        if (CrossPlatform.isWindows) "python" :: clientPath.toString :: Nil
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
    def expectSuccess(cmd: String*): Unit = {
      val exitCode = issue(cmd: _*)
      if (exitCode != 0) {
        fail(
          s"""Command failed with ${exitCode}. Logs were:
             |${log.getMessages.mkString("\n")}
             |""".stripMargin
        )
      }
    }

    /**
     * Executes a command `cmd`, expecting a non-zero exit code.
     *
     * @param cmd The command to run.
     */
    def expectFailure(cmd: String*): Unit = {
      val exitCode = issue(cmd: _*)
      if (exitCode == 0) {
        fail(
          s"""Command success but failure was expected. Logs were:
             |${log.getMessages.mkString("\n")}
             |""".stripMargin
        )
      }
    }
  }
}
