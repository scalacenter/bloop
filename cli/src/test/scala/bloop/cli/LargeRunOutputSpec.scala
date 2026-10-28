package bloop.cli

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import bloop.rifle.BloopRifle
import bloop.rifle.BloopRifleConfig
import bloop.rifle.BloopRifleLogger
import bloop.rifle.BloopThreads
import bloop.rifle.FailedToStartServerExitCodeException
import bloop.rifle.internal.Operations

import utest._

// End-to-end check for large run outputs over TCP. When the server ends the session
// while the client is still behind, the client's next heartbeat meets a closed socket:
// the client exits with 1 and loses the rest of the output. One chunk per output line
// puts a client far behind. Launches a real bloop server through bloop-rifle and runs a
// 100k-line program through the shipped client into a slightly slow sink.
object LargeRunOutputSpec extends TestSuite {

  private val lineCount = 100000
  private val runDeadline = 3.minutes
  private val writePauseNanos = 100000L

  // Spends 0.1 ms on each write, like a slow terminal or pipe. A busy wait, because a
  // timed sleep this short takes a millisecond or more on some systems. The client
  // writes one payload per received protocol chunk, so counting writes counts chunks.
  private final class ThrottledOutputStream(underlying: OutputStream) extends OutputStream {
    val writes = new java.util.concurrent.atomic.AtomicInteger(0)
    override def write(b: Int): Unit = write(Array(b.toByte), 0, 1)
    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      writes.incrementAndGet()
      val until = System.nanoTime() + writePauseNanos
      while (System.nanoTime() < until) Thread.onSpinWait()
      underlying.write(b, off, len)
    }
  }

  // Keeps the client's log: the client reports a lost connection only there, as
  // "Unexpected error forces client exit!" with the exception, and then exits with 1.
  private final class RecordingLogger extends BloopRifleLogger {
    private val entries = new ConcurrentLinkedQueue[String]()
    private def record(msg: String, ex: Throwable): Unit = {
      val trace =
        if (ex == null) ""
        else {
          val writer = new StringWriter()
          ex.printStackTrace(new PrintWriter(writer))
          "\n" + writer.toString
        }
      entries.add(msg + trace)
      ()
    }
    def info(msg: => String): Unit = record(msg, null)
    def debug(msg: => String, ex: Throwable): Unit = record(msg, ex)
    def debug(msg: => String): Unit = record(msg, null)
    def error(msg: => String, ex: Throwable): Unit = record(msg, ex)
    def error(msg: => String): Unit = record(msg, null)
    def bloopBspStdout: Option[OutputStream] = None
    def bloopBspStderr: Option[OutputStream] = None
    def bloopCliInheritStdout: Boolean = false
    def bloopCliInheritStderr: Boolean = false
    def text: String = entries.asScala.mkString("\n")
  }

  private def serverClassPath: Seq[Path] = {
    val stream = getClass.getClassLoader.getResourceAsStream("bloop-server-classpath.txt")
    assert(stream != null)
    val text =
      try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
      finally stream.close()
    text.linesIterator.filter(_.nonEmpty).map(Paths.get(_)).toVector
  }

  // The project needs compiled code but no Scala toolchain: compile a Java main with the
  // running JDK and point a source-less project at its classes. javac is forked because
  // the in-process compiler scans the test classpath for filesystem providers and chokes
  // on this module's GraalVM jars.
  private def compileFloodMain(workspace: Path): Path = {
    val classesDir = Files.createDirectories(workspace.resolve("classes"))
    val source = workspace.resolve("Flood.java")
    val code =
      s"""public class Flood {
         |  public static void main(String[] args) {
         |    for (int i = 0; i < $lineCount; i++) System.out.println("line-" + i);
         |  }
         |}
         |""".stripMargin
    Files.write(source, code.getBytes(StandardCharsets.UTF_8))
    val javac = Paths.get(sys.props("java.home"), "bin", "javac").toString
    val process = new ProcessBuilder(javac, "-d", classesDir.toString, source.toString)
      .inheritIO()
      .start()
    val result = process.waitFor()
    assert(result == 0)
    classesDir
  }

  // Schema version of the hand-written config below. Pinned deliberately: this module
  // cannot depend on bloop-config, and the test only needs a project bloop can run.
  private val configSchemaVersion = "1.4.0"

  private def jsonPath(path: Path): String = path.toString.replace("\\", "\\\\")

  private def writeConfig(workspace: Path, classesDir: Path): Path = {
    val configDir = Files.createDirectories(workspace.resolve(".bloop"))
    val outDir = Files.createDirectories(workspace.resolve("out"))
    val json =
      s"""{
         |  "version": "$configSchemaVersion",
         |  "project": {
         |    "name": "flood",
         |    "directory": "${jsonPath(workspace)}",
         |    "workspaceDir": "${jsonPath(workspace)}",
         |    "sources": [],
         |    "dependencies": [],
         |    "classpath": ["${jsonPath(classesDir)}"],
         |    "out": "${jsonPath(outDir)}",
         |    "classesDir": "${jsonPath(classesDir)}"
         |  }
         |}
         |""".stripMargin
    Files.write(configDir.resolve("flood.json"), json.getBytes(StandardCharsets.UTF_8))
    configDir
  }

  private def freePort(): Int = {
    val socket = new java.net.ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  // Binding port 0 and closing it leaves a window in which another process can take the
  // port, so a start that loses that race is retried on a fresh one.
  private def startServer(
      workspace: Path,
      threads: BloopThreads,
      logger: BloopRifleLogger,
      attemptsLeft: Int = 3
  ): BloopRifleConfig = {
    // The server needs the JDK the build targets, not whatever `java` is on PATH.
    val javaExec = Paths.get(sys.props("java.home"), "bin", "java").toString
    // TCP, because the lost output was reported and reproduced over TCP.
    val config = BloopRifleConfig
      .default(
        BloopRifleConfig.Address.Tcp("127.0.0.1", freePort()),
        _ => Right(serverClassPath),
        workspace
      )
      .copy(javaPath = javaExec)
    try {
      val started = BloopRifle.startServer(
        config,
        threads.startServerChecks,
        logger,
        config.retainedBloopVersion.version.raw,
        config.javaPath
      )
      Await.result(started, 2.minutes)
      config
    } catch {
      // Losing the race kills the server process, which fails fast with this. A start
      // that times out has a live but unreachable server, which a fresh port cannot fix.
      case _: FailedToStartServerExitCodeException if attemptsLeft > 1 =>
        try BloopRifle.exit(config, workspace, logger)
        catch { case NonFatal(_) => () }
        startServer(workspace, threads, logger, attemptsLeft - 1)
    }
  }

  // The graceful stop is itself a synchronous nailgun call that could hang against a
  // wedged server, so it runs under a deadline. Returns its exit code, or -1 if it failed
  // or timed out. Kept out of the test's `finally` block: inlined there, Scala 3.3 fails
  // with "Error while emitting".
  private def stopServer(
      config: BloopRifleConfig,
      workspace: Path,
      logger: BloopRifleLogger
  ): Int = {
    val stopExecutor = Executors.newSingleThreadExecutor { (r: Runnable) =>
      val t = new Thread(r, "large-run-server-stop")
      t.setDaemon(true)
      t
    }
    try {
      val stopping =
        Future(BloopRifle.exit(config, workspace, logger))(
          ExecutionContext.fromExecutor(stopExecutor)
        )
      Await.result(stopping, 30.seconds)
    } catch { case NonFatal(_) => -1 }
    finally { stopExecutor.shutdownNow(); () }
  }

  // The server JVM is spawned as a child process; kill it directly if the graceful
  // nailgun stop failed or timed out, so a wedged server cannot outlive the test.
  private def forceStopServer(): Unit = {
    ProcessHandle
      .current()
      .children()
      .iterator()
      .asScala
      .toList
      .filter { handle =>
        val info = handle.info()
        // Windows leaves `commandLine` empty, so fall back to the executable there: the
        // only JVM this test leaves running as its own child is the server.
        info.commandLine().orElse("").contains("bloop.BloopServer") ||
        (info.commandLine().isEmpty && info.command().orElse("").contains("java"))
      }
      .foreach { handle => handle.destroyForcibly(); () }
  }

  private def deleteRecursively(root: Path): Unit =
    try {
      if (Files.exists(root)) {
        val paths = Files.walk(root)
        try paths.sorted(Comparator.reverseOrder()).forEach { p => Files.deleteIfExists(p); () }
        finally paths.close()
      }
    } catch { case NonFatal(_) => () }

  val tests: Tests = Tests {
    test("bloop run delivers all 100k lines to a slightly slow client over TCP") {
      val workspace = Files.createTempDirectory("bloop-large-run-")
      val threads = BloopThreads.create()
      val runExecutor = Executors.newSingleThreadExecutor { (r: Runnable) =>
        val t = new Thread(r, "large-run-client")
        t.setDaemon(true)
        t
      }
      val logger = new RecordingLogger
      var serverConfig = Option.empty[BloopRifleConfig]
      try {
        val config = startServer(workspace, threads, logger)
        serverConfig = Some(config)
        val classesDir = compileFloodMain(workspace)
        val configDir = writeConfig(workspace, classesDir)
        val outBytes = new ByteArrayOutputStream()
        val out = new ThrottledOutputStream(outBytes)
        val err = new ByteArrayOutputStream()
        val clientEc = ExecutionContext.fromExecutor(runExecutor)
        val run = Future {
          Operations.run(
            command = "run",
            args = Array("flood", "--config-dir", configDir.toString, "-m", "Flood"),
            workingDir = workspace,
            address = config.address,
            inOpt = None,
            out = out,
            err = err,
            logger = logger
          )
        }(clientEc)
        // If the run never ends, fail loudly instead of hanging the test worker, and
        // let the finally block tear everything down.
        val exitCode =
          try Await.result(run, runDeadline)
          catch {
            case _: TimeoutException =>
              sys.error(s"`bloop run` of $lineCount lines did not complete within $runDeadline")
          }

        val stdout = new String(outBytes.toByteArray, StandardCharsets.UTF_8)
        val stderr = new String(err.toByteArray, StandardCharsets.UTF_8)
        val programLines = stdout.linesIterator.filter(_.startsWith("line-")).toVector
        val firstWrongLine = programLines.indices.find(i => programLines(i) != "line-" + i)
        val chunks = out.writes.get
        val passed = exitCode == 0 && programLines.size == lineCount &&
          firstWrongLine.isEmpty && chunks < 2000
        // One message with everything that tells the client, the server and the
        // environment apart, because a failure on CI cannot be rerun with more output.
        if (!passed)
          throw new java.lang.AssertionError(
            s"exit code $exitCode, ${programLines.size} of $lineCount lines, " +
              s"first wrong line: ${firstWrongLine.getOrElse("none")}, $chunks chunks\n" +
              s"stderr:\n$stderr\nclient log:\n${logger.text}"
          )
      } finally {
        // Stop the (possibly stuck) client before any further nailgun operation.
        runExecutor.shutdownNow()
        // A start that gave up can still have left its last server process behind.
        val stopped = serverConfig.map(stopServer(_, workspace, logger)).getOrElse(-1)
        if (stopped != 0) forceStopServer()
        threads.startServerChecks.shutdownNow()
        threads.shutdown()
        deleteRecursively(workspace)
        ()
      }
    }
  }
}
