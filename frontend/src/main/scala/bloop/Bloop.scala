package bloop

import java.net.InetAddress

import bloop.logging.BloopLogger
import bloop.logging.Logger
import bloop.util.ProxySetup

import java.io.InputStream
import java.io.PrintStream
import java.nio.channels.ReadableByteChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

import com.martiansoftware.nailgun.NGListeningAddress
import com.martiansoftware.nailgun.NGConstants
import com.martiansoftware.nailgun.{Alias, NGContext, NGServer}
import libdaemonjvm._
import libdaemonjvm.internal.{LockProcess, SocketHandler}
import libdaemonjvm.server._

import scala.util.Properties
import scala.util.Try
import java.net.ServerSocket
import java.net.Socket
import java.io.OutputStream
import java.net.SocketAddress
import java.nio.channels.Channels
import java.nio.ByteBuffer
import java.io.File
import org.slf4j
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.Path
import sun.misc.{Signal, SignalHandler}

sealed abstract class Bloop

object Bloop {
  private def ensureSafeDirectoryExists(dir: Path): Unit =
    if (!Files.exists(dir)) {
      Files.createDirectories(dir)
      if (!Properties.isWin)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
    }
  private val defaultPort: Int = 8212 // 8100 + 'p'
  def main(args: Array[String]): Unit = {
    def toPortNumber(userPort: String) = Try(userPort.toInt).getOrElse(Bloop.defaultPort)
    val lockFilesOrHostPort = args match {
      case Array() =>
        val dir = bloop.io.Paths.daemonDir.underlying
        ensureSafeDirectoryExists(dir)
        val lockFiles = LockFiles.under(dir)
        Right(lockFiles)
      case Array(daemonArg) if daemonArg.startsWith("daemon:") =>
        val dir = Paths.get(daemonArg.stripPrefix("daemon:"))
        ensureSafeDirectoryExists(dir)
        val lockFiles = LockFiles.under(dir)
        Right(lockFiles)
      case Array(arg) =>
        Left((InetAddress.getLoopbackAddress(), toPortNumber(args(0))))
      case Array(host, portStr) =>
        val addr = InetAddress.getByName(host)
        Left((addr, toPortNumber(portStr)))
      case _ =>
        throw new IllegalArgumentException(
          s"Invalid arguments to bloop server: $args, expected: ([address] [port] | [daemon:path])"
        )
    }

    if (java.lang.Boolean.getBoolean("bloop.ignore-sig-int"))
      ignoreSigint()

    lockFilesOrHostPort match {
      case Left(hostPort) =>
        startServer(Left(hostPort))
      case Right(lockFiles) =>
        Lock.tryAcquire(lockFiles, LockProcess.default) {
          startServer(Right(lockFiles.socketPaths))
        } match {
          case Left(err) => throw new Exception(err)
          case Right(()) =>
        }
    }
  }

  private def ignoreSigint(): Unit = {
    Signal.handle(
      new Signal("INT"),
      signal => {
        System.err.println("Ignoring Ctrl+C interruption")
      }
    )
    ()
  }

  def startServer(socketPathsOrHostPort: Either[(InetAddress, Int), SocketPaths]): Unit = {
    val socketAndPathOrHostPort = socketPathsOrHostPort.map { socketPaths =>
      val socket = libdaemonjvm.Util.serverSocketFromChannel(
        SocketHandler.server(socketPaths)
      )
      (socket, socketPaths.path)
    }
    val server = instantiateServer(socketAndPathOrHostPort.map {
      case (sock, path) => (sock, path.toString)
    })
    val runServer: Runnable = () =>
      try server.run()
      finally {
        for (path <- socketAndPathOrHostPort.toOption.map(_._2))
          Files.deleteIfExists(path)
      }
    // FIXME Small delay between the time this method returns, and the time we actually
    // accept connections on the socket. This might make concurrent attempts to start a server
    // think we are a zombie server, and attempt to listen on the socket too.
    new Thread(runServer, "bloop-server").start()
  }

  private[bloop] def instantiateServer(
      socketAndPathOrHostPort: Either[(InetAddress, Int), (ServerSocket, String)]
  ): NGServer = {
    val logger = BloopLogger.default("bloop-nailgun-main")
    socketAndPathOrHostPort match {
      case Left((addr, port)) =>
        val tcpAddress = new NGListeningAddress(addr, port)
        launchServer(System.in, System.out, System.err, tcpAddress, logger, None)
      case Right((socket, socketPath)) =>
        val socketAddress = new NGListeningAddress(socketPath)
        launchServer(System.in, System.out, System.err, socketAddress, logger, Some(socket))
    }
  }

  private[bloop] def launchServer(
      in: InputStream,
      out: PrintStream,
      err: PrintStream,
      address: NGListeningAddress,
      logger: Logger,
      serverSocketOpt: Option[ServerSocket]
  ): NGServer = {
    val javaLogger = slf4j.LoggerFactory.getLogger(classOf[NGServer])
    val poolSize = NGServer.DEFAULT_SESSIONPOOLSIZE
    val heartbeatMs = NGConstants.HEARTBEAT_TIMEOUT_MILLIS.toInt

    val domainSocketProvider: NGServer.DomainSocketProvider = { () =>
      serverSocketOpt.getOrElse(
        sys.error("Shouldn't be called")
      )
    }

    val server =
      new NGServer(address, poolSize, heartbeatMs, in, out, err, javaLogger, domainSocketProvider)
    registerAliases(server)
    ProxySetup.init()
    server
  }

  def nailMain(ngContext: NGContext): Unit = {
    val server = ngContext.getNGServer

    val soft = ngContext.getArgs.contains("--soft")

    // Passing true by default to force exiting the JVM (System.exit).
    // When using JNI/JNA-based domain sockets, it seems the call to accept()
    // isn't interrupted when the underlying socket is closed (on Linux at least).
    // So we have to force a call to System.exit to actually exit.
    server.shutdown(!soft)
  }

  private def registerAliases(server: NGServer): Unit = {
    val aliasManager = server.getAliasManager
    aliasManager.addAlias(new Alias("about", "Show bloop information.", classOf[Cli]))
    aliasManager.addAlias(new Alias("clean", "Clean project(s) in the build.", classOf[Cli]))
    aliasManager.addAlias(new Alias("compile", "Compile project(s) in the build.", classOf[Cli]))
    aliasManager.addAlias(new Alias("test", "Run project(s)' tests in the build.", classOf[Cli]))
    aliasManager.addAlias(
      new Alias("run", "Run a main entrypoint for project(s) in the build.", classOf[Cli])
    )
    aliasManager.addAlias(new Alias("bsp", "Spawn a build server protocol instance.", classOf[Cli]))
    aliasManager.addAlias(
      new Alias("console", "Run the console for project(s) in the build.", classOf[Cli])
    )
    aliasManager.addAlias(new Alias("projects", "Show projects in the build.", classOf[Cli]))
    aliasManager.addAlias(new Alias("configure", "Configure the bloop server.", classOf[Cli]))
    aliasManager.addAlias(new Alias("help", "Show bloop help message.", classOf[Cli]))
    aliasManager.addAlias(
      new Alias(
        "exit",
        "Kill the bloop server.",
        classOf[Bloop]
      )
    )
    aliasManager.addAlias(
      new Alias(
        "ng-stop",
        "Kill the bloop server.",
        classOf[Bloop]
      )
    )

    // Register the default entrypoint in case the user doesn't use the right alias
    server.setDefaultNailClass(classOf[Cli])
    // Disable nails by class name so that we prevent classloading incorrect aliases
    server.setAllowNailsByClassName(false)
  }
}
