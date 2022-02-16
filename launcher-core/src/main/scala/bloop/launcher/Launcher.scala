package bloop.launcher

import java.io._
import java.net.Socket
import java.net.URL
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file._

import scala.util.Try
import scala.concurrent.Promise

import bloop.launcher.LauncherStatus.{
  FailedLauncherStatus,
  FailedToConnectToServer,
  FailedToInstallBloop,
  FailedToOpenBspConnection,
  FailedToParseArguments,
  SuccessfulRun
}
import bloop.launcher.bsp.{BspBridge, BspConnection}
import bloop.launcher.core.Feedback
import bloop.bloopgun.util.Environment
import bloop.bloopgun.core.{Shell, ServerStatus}
import bloop.bloopgun.BloopgunCli
import bloop.bloopgun.Defaults

object Launcher
    extends LauncherMain(
      System.in,
      System.out,
      System.err,
      StandardCharsets.UTF_8,
      Shell.default,
      userNailgunHost = None,
      userNailgunPort = None,
      Promise[Unit]()
    )

class LauncherMain(
    clientIn: InputStream,
    clientOut: OutputStream,
    val out: PrintStream,
    charset: Charset,
    val shell: Shell,
    val userNailgunHost: Option[String],
    val userNailgunPort: Option[Int],
    startedServer: Promise[Unit]
) {
  private final val launcherTmpDir = Files.createTempDirectory(s"bsp-launcher")
  private final val nailgunHost = userNailgunHost.getOrElse(Defaults.Host)
  private final val nailgunPort = userNailgunPort.getOrElse(Defaults.Port).toString
  private final val bloopAdditionalCliArgs: List[String] = {
    val hostArg = if (nailgunHost == Defaults.Host) Nil else List("--nailgun-host", nailgunHost)
    val portArg = if (nailgunPort == Defaults.Port) Nil else List("--nailgun-port", nailgunPort)
    portArg ++ hostArg
  }

  def main(args: Array[String]): Unit = {
    cli(args) match {
      case SuccessfulRun => sys.exit(0)
      case _: FailedLauncherStatus => sys.exit(1)
    }
  }

  private final val SkipBspConnection = "--skip-bsp-connection"
  def cli(args0: Array[String]): LauncherStatus = {
    // A poor man's implementation of a CLI
    val (args, extraArgs) = {
      val index = args0.indexOf("--")
      if (index == -1) (args0, Array.empty[String])
      else args0.splitAt(index)
    }

    val (userJvmOptions, cliOptions) = args.partition(_.startsWith("-J"))
    val (cliFlags, cliArgs) = cliOptions.toList.partition(_.startsWith("--"))
    val skipBspConnection = cliFlags.exists(_ == SkipBspConnection)

    val defaultJvmOptions = bloop.bloopgun.util.Environment.PerformanceSensitiveOptsForBloop
    val allJvmOptions = defaultJvmOptions ++ userJvmOptions
    if (cliArgs.size == 1) {
      val bloopVersion = cliArgs.apply(0)
      runLauncher(bloopVersion, skipBspConnection, allJvmOptions)
    } else {
      printError(Feedback.NoBloopVersion, out)
      FailedToParseArguments
    }
  }

  def runLauncher(
      bloopVersionToInstall: String,
      skipBspConnection: Boolean,
      serverJvmOptions: List[String]
  ): LauncherStatus = {
    def failPromise(status: LauncherStatus) =
      startedServer.failure(new RuntimeException(s"The server did not start, got $status"))
    println("Starting the bsp launcher for bloop...", out)
    val bridge = new BspBridge(clientIn, clientOut, startedServer, out, shell, launcherTmpDir)

    connectToBloopBspServer(
      bloopVersionToInstall,
      skipBspConnection,
      bridge,
      serverJvmOptions
    ) match {
      case Right(Left(_)) => SuccessfulRun

      case Right(Right(Some(socket))) =>
        try {
          bridge.wireBspConnectionStreams(socket)
        } finally {
          if (socket.isConnected()) {
            socket.close()
          }
        }
        SuccessfulRun

      case Right(Right(None)) =>
        failPromise(FailedToOpenBspConnection)
        FailedToOpenBspConnection

      case Left(status) =>
        failPromise(status)
        status
    }
  }

  // A connection result can be empty if no bsp connection is done, otherwise a socket
  type ConnectionResult = Either[Unit, Option[Socket]]
  def connectToBloopBspServer(
      bloopVersion: String,
      skipBspConnection: Boolean,
      bridge: BspBridge,
      serverJvmOptions: List[String]
  ): Either[LauncherStatus, ConnectionResult] = {
    def openBspSocket(forceTcp: Boolean = false)(
        connect: Boolean => bridge.RunningBspConnection
    ): Either[LauncherStatus, Either[Unit, Option[Socket]]] = {
      val connection = connect(forceTcp)
      bridge.waitForOpenBsp(connection) match {
        case Some(c) => Right(Right(bridge.connectToOpenSession(c)))
        case None =>
          connection match {
            case bridge.RunningBspConnection(BspConnection.Tcp(_, _), _) =>
              Left {
                printError("The launcher failed to establish a bsp connection, aborting...", out)
                FailedToOpenBspConnection
              }

            case bridge.RunningBspConnection(connection, logs) =>
              printError("Trying a tcp-based connection to the server instead...", out)
              openBspSocket(forceTcp = true)(connect)
          }
      }
    }

    if (skipBspConnection) {
      val bloopgunArgs = List("server", "--fire-and-forget", nailgunPort) ++ serverJvmOptions
      val bloopgun = newBloopgunCli(bloopVersion, out)
      Try(bloopgun.run(bloopgunArgs.toArray)).toEither match {
        case Right(code) if code == 0 => Right(Left(()))
        case Right(errorCode) =>
          out.println(s"Error when starting server: $errorCode")
          Left(FailedToConnectToServer)
        case Left(t) =>
          out.println("Exception caught when starting server!")
          t.printStackTrace(out)
          Left(FailedToConnectToServer)
      }
    } else {
      openBspSocket(forceTcp = false) { useTcp =>
        bridge.establishBspConnectionViaBinary(
          out => newBloopgunCli(bloopVersion, out),
          bloopAdditionalCliArgs,
          useTcp
        )
      }
    }
  }

  def newBloopgunCli(bloopVersion: String, out: PrintStream): BloopgunCli = {
    val dummyIn = new ByteArrayInputStream(new Array(0))
    new BloopgunCli(bloopVersion, dummyIn, out, out, shell)
  }
}
