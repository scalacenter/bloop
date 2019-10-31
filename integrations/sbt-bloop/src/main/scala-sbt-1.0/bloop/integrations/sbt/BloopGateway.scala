package bloop.integrations.sbt

import sbt.Logger
import java.nio.file.Path

import xsbti.{Reporter => CompileReporter}
import bloop.bloop4j.api.NakedLowLevelBuildClient
import bloop.bloop4j.api.handlers.BuildClientHandlers
import java.nio.channels.Pipe
import java.nio.channels.Channels
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import bloop.bloopgun.core.Shell
import scala.concurrent.Promise
import java.util.concurrent.atomic.AtomicBoolean
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import bloop.launcher.LauncherMain
import java.util.concurrent.atomic.AtomicReference
import bloop.launcher.LauncherStatus

object BloopGateway {

  case class ConnectionState(
      baseDir: Path,
      running: Promise[Unit],
      exitStatus: AtomicReference[Option[LauncherStatus]],
      clientIn: InputStream,
      clientOut: OutputStream,
      logFile: Path,
      logOut: PrintStream
  )

  /**
   * Connects to the Bloop server via Bloopgun. Bloopgun will run the server in
   * the background and its execution might trigger the download of the Bloop
   * server if it isn't installed as well as its execution if it isn't running.
   * This process also asks for a particular version of the Bloop server. In
   * case that version doesn't match the version of the running server or a
   * higher version, Bloopgun will force the exit of the running server and
   * launch the appropriate one.
   *
   * This method is usually called at the beginning of the build to make sure
   * there's a running bloop server when the compile tasks attempt to connect.
   * It does not initialize the bsp connection, only establishes a connection
   * with open stream to read from and write to the bloop server.
   */
  def connectOnTheBackgroundTo(baseDir: Path): ConnectionState = {
    import scala.concurrent.Promise
    val firstPipe = Pipe.open()
    val launcherIn = Channels.newInputStream(firstPipe.source())
    val clientOut = Channels.newOutputStream(firstPipe.sink())

    val secondPipe = Pipe.open()
    val clientIn = Channels.newInputStream(secondPipe.source())
    val launcherOut = Channels.newOutputStream(secondPipe.sink())

    val bloopDir = Files.createDirectories(baseDir.resolve(".bloop"))
    val logFile = bloopDir.resolve("bloop.log")
    val logOut = new PrintStream(Files.newOutputStream(logFile))

    val charset = StandardCharsets.UTF_8
    val shell = Shell.default
    val started = Promise[Unit]()
    val launcher =
      new LauncherMain(launcherIn, launcherOut, logOut, charset, shell, None, None, started)

    val exitStatus = new AtomicReference[Option[LauncherStatus]](None)
    val launcherThread = new Thread {
      override def run(): Unit = {
        import bloop.launcher.LauncherStatus
        import bloopgun.internal.build.BloopgunInfo.version
        try {
          val launcherStatus = launcher.cli(Array(version))
          exitStatus.set(Some(launcherStatus))
          logOut.println(s"Bloop launcher exited with ${launcherStatus}")
        } catch {
          case t: Throwable =>
            // Add most likely launcher error status if we got exception
            exitStatus.set(Some(LauncherStatus.FailedToConnectToServer))
            logOut.println("Unexpected error stopped the Bloop launcher!")
            t.printStackTrace(logOut)
        }
        ()
      }
    }

    launcherThread.setDaemon(true)
    launcherThread.start()

    ConnectionState(baseDir, started, exitStatus, clientIn, clientOut, logFile, logOut)
  }
}
