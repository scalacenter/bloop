package bloop

import java.io.InputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.DurationInt
import scala.util.Properties
import scala.util.Try

import bloop.logging.BloopLogger
import bloop.logging.Logger
import bloop.util.ProxySetup

import com.martiansoftware.nailgun.Alias
import com.martiansoftware.nailgun.NGConstants
import com.martiansoftware.nailgun.NGContext
import com.martiansoftware.nailgun.NGListeningAddress
import com.martiansoftware.nailgun.NGServer
import libdaemonjvm._
import libdaemonjvm.internal.LockProcess
import libdaemonjvm.internal.SocketHandler
import libdaemonjvm.server._
import org.slf4j
import sun.misc.Signal

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

    val pid = ProcessHandle.current().pid()
    System.err.println(s"Bloop server PID: $pid")

    if (java.lang.Boolean.getBoolean("bloop.ignore-sig-int")) {
      System.err.println("Ignoring SIGINT")
      ignoreSigint()
    }

    for (value <- sys.props.get("bloop.truncate-output-file-periodically")) {
      System.err.println(s"Will truncate output file $value every 5 minutes")
      truncateFilePeriodically(Paths.get(value))
    }

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

  private def truncateFilePeriodically(file: Path): Unit = {
    val scheduler = Executors.newSingleThreadScheduledExecutor(
      new ThreadFactory {
        val count = new AtomicInteger
        def newThread(r: Runnable): Thread = {
          val t = new Thread(r, s"truncate-file-${count.incrementAndGet()}")
          t.setDaemon(true)
          t
        }
      }
    )
    val period = 5.minutes
    val maxSize = 1024 * 1024 // 1 MiB
    val runnable: Runnable =
      () =>
        try {
          if (Files.exists(file)) {
            val size = Files.size(file)
            if (size > maxSize) {
              var bc: SeekableByteChannel = null
              try {
                bc = Files.newByteChannel(file, StandardOpenOption.WRITE)
                bc.truncate(0L)
              } finally {
                if (bc != null)
                  bc.close()
              }

              // Seems closing / re-opening the output file is needed for truncation to work
              val ps = new PrintStream(Files.newOutputStream(file))
              val formerOut = System.out
              val formerErr = System.err
              System.setOut(ps)
              System.setErr(ps)
              formerOut.close()
              formerErr.close()

              System.err.println(s"Truncated $file (former size: $size B)")
              ()
            }
          }
        } catch {
          case t: Throwable =>
            System.err.println(
              s"Caught $t while checking if $file needs to be truncated, ignoring it"
            )
            t.printStackTrace(System.err)
        }
    scheduler.scheduleAtFixedRate(
      runnable,
      period.length,
      period.length,
      period.unit
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
