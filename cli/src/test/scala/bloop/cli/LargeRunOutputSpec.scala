package bloop.cli

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.concurrent.Executors

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

import bloop.rifle.BloopRifle
import bloop.rifle.BloopRifleConfig
import bloop.rifle.BloopRifleLogger
import bloop.rifle.BloopThreads
import bloop.rifle.FailedToStartServerExitCodeException
import bloop.rifle.internal.Operations

import utest._

// End-to-end acceptance for the large-run-output failure: unbatched output floods the
// nailgun socket with one chunk per line until the session dies mid-run with a
// connection reset or never completes. Launches a real bloop server through bloop-rifle
// and runs a 100k-line program through the shipped client, writing to a slow sink like
// a terminal's, under a hard deadline.
object LargeRunOutputSpec extends TestSuite {

  private val lineCount = 100000
  private val runDeadline = 3.minutes

  // Briefly blocks each write, like a terminal or slow pipe, so the shipped client's
  // read loop experiences output backpressure. The client writes one payload per
  // received protocol chunk, so counting writes counts chunks.
  private final class ThrottledOutputStream(underlying: OutputStream) extends OutputStream {
    val writes = new java.util.concurrent.atomic.AtomicInteger(0)
    override def write(b: Int): Unit = {
      writes.incrementAndGet()
      Thread.sleep(10)
      underlying.write(b)
    }
    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      writes.incrementAndGet()
      Thread.sleep(10)
      underlying.write(b, off, len)
    }
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

  // The server JVM is spawned as a child process; kill it directly if the graceful
  // nailgun stop failed or timed out, so a wedged server cannot outlive the test.
  private def forceStopServer(): Unit = {
    import scala.collection.JavaConverters._
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
    test("bloop run delivers 100k lines through the shipped client under backpressure") {
      val workspace = Files.createTempDirectory("bloop-large-run-")
      val threads = BloopThreads.create()
      val runExecutor = Executors.newSingleThreadExecutor { (r: Runnable) =>
        val t = new Thread(r, "large-run-client")
        t.setDaemon(true)
        t
      }
      val logger = BloopRifleLogger.nop
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
        // A hang is this issue's reported failure mode: fail loudly instead of
        // hanging the test worker, and let the finally block tear everything down.
        val exitCode =
          try Await.result(run, runDeadline)
          catch {
            case _: TimeoutException =>
              sys.error(s"`bloop run` of $lineCount lines did not complete within $runDeadline")
          }

        val stdout = new String(outBytes.toByteArray, StandardCharsets.UTF_8)
        val stderr = new String(err.toByteArray, StandardCharsets.UTF_8)
        val programLines = stdout.linesIterator.filter(_.startsWith("line-")).toList
        val expected = (0 until lineCount).map("line-" + _).toList
        val chunks = out.writes.get
        assert(exitCode == 0)
        assert(programLines == expected)
        assert(!stderr.contains("Connection reset"))
        assert(chunks < 2000)
      } finally {
        // Stop the (possibly stuck) client before any further nailgun operation, and
        // bound the graceful server stop: it is itself a synchronous nailgun call that
        // could hang against a wedged server.
        runExecutor.shutdownNow()
        val stopExecutor = Executors.newSingleThreadExecutor { (r: Runnable) =>
          val t = new Thread(r, "large-run-server-stop")
          t.setDaemon(true)
          t
        }
        val stopped = serverConfig match {
          case Some(config) =>
            try {
              val stopping =
                Future(BloopRifle.exit(config, workspace, logger))(
                  ExecutionContext.fromExecutor(stopExecutor)
                )
              Await.result(stopping, 30.seconds)
            } catch { case NonFatal(_) => -1 }
          // A start that gave up can still have left its last server process behind.
          case None => -1
        }
        if (stopped != 0) forceStopServer()
        stopExecutor.shutdownNow()
        threads.startServerChecks.shutdownNow()
        threads.shutdown()
        deleteRecursively(workspace)
        ()
      }
    }
  }
}
