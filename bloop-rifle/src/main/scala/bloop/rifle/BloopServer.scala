package bloop.rifle

import ch.epfl.scala.bsp4j
import bloop.rifle.BloopRifleConfig.{AtLeast, Strict}
import bloop.rifle.BuildServer
import bloop.rifle.bloop4j.BloopExtraBuildParams
import bloop.rifle.internal.BuildInfo
import org.eclipse.lsp4j.jsonrpc

import java.io.{File, IOException}
import java.net.{ConnectException, Socket}
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Future => JFuture, ScheduledExecutorService, TimeoutException}

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

trait BloopServer {
  def server: BuildServer

  def shutdown(): Unit

  def bloopInfo: BloopRifle.BloopServerRuntimeInfo
}

object BloopServer {
  private case class BloopServerImpl(
      server: BuildServer,
      listeningFuture: JFuture[Void],
      socket: Socket,
      bloopInfo: BloopRifle.BloopServerRuntimeInfo
  ) extends BloopServer {
    def shutdown(): Unit = {
      // Close the jsonrpc thread listening to input messages
      // First line makes jsonrpc discard the closed connection exception.
      listeningFuture.cancel(true)
      socket.close()
    }
  }

  private case class ResolvedBloopParameters(
      bloopVersion: BloopVersion,
      jvmRelease: Int,
      javaPath: String
  )

  private def resolveBloopInfo(
      bloopInfo: BloopRifle.BloopServerRuntimeInfo,
      config: BloopRifleConfig
  ): ResolvedBloopParameters = {
    val bloopV: BloopVersion = config.retainedBloopVersion match {
      case AtLeast(version) =>
        val ord = Ordering.fromLessThan[BloopVersion](_ isOlderThan _)
        Seq(bloopInfo.bloopVersion, version).max(ord)
      case Strict(version) => version
    }
    val jvmV = List(bloopInfo.jvmVersion, config.minimumBloopJvm).max
    val bloopInfoJava = Paths.get(bloopInfo.javaHome, "bin", "java").toString()
    val expectedJava = if (jvmV >= bloopInfo.jvmVersion) config.javaPath else bloopInfoJava
    ResolvedBloopParameters(bloopV, jvmV, expectedJava)
  }

  private def ensureBloopRunning(
      config: BloopRifleConfig,
      startServerChecksPool: ScheduledExecutorService,
      logger: BloopRifleLogger
  ): BloopRifle.BloopServerRuntimeInfo = {
    val workdir = new File(".").getCanonicalFile.toPath
    def startBloop(bloopVersion: String, bloopJava: String) = {
      val fut = BloopRifle.startServer(
        config,
        startServerChecksPool,
        logger,
        bloopVersion,
        bloopJava
      )
      Await.result(fut, config.startCheckTimeout + 30.seconds)
    }
    def exitBloop() = BloopRifle.exit(config, workdir, logger)

    val bloopInfo =
      BloopRifle.getCurrentBloopVersion(config, logger, workdir, startServerChecksPool)
    val isRunning = BloopRifle.check(config, logger)
    val ResolvedBloopParameters(expectedBloopVersion, expectedBloopJvmRelease, javaPath) =
      bloopInfo match {
        case Left(error) =>
          error match {
            case BloopRifle.BloopNotRunning =>
            case BloopRifle.ParsingFailed(bloopAboutOutput) =>
              logger.info(s"Failed to parse output of 'bloop about':\n$bloopAboutOutput")
          }
          ResolvedBloopParameters(
            config.retainedBloopVersion.version,
            config.minimumBloopJvm,
            config.javaPath
          )
        case Right(value) => resolveBloopInfo(value, config)
      }
    val bloopVersionIsOk = bloopInfo.exists(_.bloopVersion == expectedBloopVersion)
    val bloopJvmIsOk = bloopInfo.exists(_.jvmVersion == expectedBloopJvmRelease)
    val isOk = bloopVersionIsOk && bloopJvmIsOk

    if (!isOk) {
      logger.debug(s"Bloop daemon status: ${bloopInfo.fold(_.message, _.message)}")
      if (isRunning) exitBloop()
      startBloop(expectedBloopVersion.raw, javaPath)
    }

    BloopRifle
      .getCurrentBloopVersion(config, logger, workdir, startServerChecksPool)
      .fold(
        e => throw new RuntimeException(s"Fatal error, could not spawn Bloop: ${e.message}"),
        identity
      )
  }

  private def connect(
      conn: BspConnection,
      period: FiniteDuration,
      timeout: FiniteDuration
  ): Socket = {

    @tailrec
    def create(stopAt: Long): Socket = {
      val maybeSocket =
        try Right(conn.openSocket(period, timeout))
        catch {
          case e: ConnectException => Left(e)
        }
      maybeSocket match {
        case Right(socket) => socket
        case Left(e) =>
          if (System.currentTimeMillis() >= stopAt)
            throw new IOException(s"Can't connect to ${conn.address}", e)
          else {
            Thread.sleep(period.toMillis)
            create(stopAt)
          }
      }
    }

    create(System.currentTimeMillis() + timeout.toMillis)
  }

  def bsp(
      config: BloopRifleConfig,
      workspace: Path,
      threads: BloopThreads,
      logger: BloopRifleLogger,
      period: FiniteDuration,
      timeout: FiniteDuration
  ): (BspConnection, Socket, BloopRifle.BloopServerRuntimeInfo) = {

    val bloopInfo = ensureBloopRunning(config, threads.startServerChecks, logger)

    logger.debug("Opening BSP connection with bloop")
    Files.createDirectories(workspace.resolve(".bloop"))
    val conn = BloopRifle.bsp(
      config,
      workspace,
      logger
    )
    logger.debug(s"Bloop BSP connection waiting at ${conn.address}")

    val socket = connect(conn, period, timeout)

    logger.debug(s"Connected to Bloop via BSP at ${conn.address}")

    (conn, socket, bloopInfo)
  }

  def buildServer(
      config: BloopRifleConfig,
      clientName: String,
      clientVersion: String,
      workspace: Path,
      classesDir: Path,
      buildClient: bsp4j.BuildClient,
      threads: BloopThreads,
      logger: BloopRifleLogger
  ): BloopServer = {

    val (conn, socket, bloopInfo) =
      bsp(config, workspace, threads, logger, config.period, config.timeout)

    logger.debug(s"Connected to Bloop via BSP at ${conn.address}")

    // FIXME As of now, we don't detect when connection gets closed.
    // For TCP connections, this should be do-able with heartbeat messages
    // (to be added to BSP?).
    // For named sockets, the recv system call is supposed to allow to detect
    // that case, unlike the read system call. But the ipcsocket library that we use
    // for named sockets relies on read.

    val launcher = new jsonrpc.Launcher.Builder[BuildServer]()
      .setExecutorService(threads.jsonrpc)
      .setInput(socket.getInputStream)
      .setOutput(socket.getOutputStream)
      .setRemoteInterface(classOf[BuildServer])
      .setLocalService(buildClient)
      .create()
    val server = launcher.getRemoteProxy

    val f = launcher.startListening()

    val initParams = new bsp4j.InitializeBuildParams(
      clientName,
      clientVersion,
      BuildInfo.bspVersion,
      workspace.toUri.toASCIIString,
      new bsp4j.BuildClientCapabilities(List("scala", "java").asJava)
    )
    val bloopExtraParams = new BloopExtraBuildParams
    bloopExtraParams.setClientClassesRootDir(classesDir.toUri.toASCIIString)
    bloopExtraParams.setOwnsBuildFiles(true)
    initParams.setData(bloopExtraParams)
    logger.debug("Sending buildInitialize BSP command to Bloop")
    try server.buildInitialize(initParams).get(config.initTimeout.length, config.initTimeout.unit)
    catch {
      case ex: TimeoutException =>
        throw new Exception("Timeout while waiting for buildInitialize response", ex)
    }

    server.onBuildInitialized()
    BloopServerImpl(server, f, socket, bloopInfo)
  }

  def withBuildServer[T](
      config: BloopRifleConfig,
      clientName: String,
      clientVersion: String,
      workspace: Path,
      classesDir: Path,
      buildClient: bsp4j.BuildClient,
      threads: BloopThreads,
      logger: BloopRifleLogger
  )(f: BloopServer => T): T = {
    var server: BloopServer = null
    try {
      server = buildServer(
        config,
        clientName,
        clientVersion,
        workspace,
        classesDir,
        buildClient,
        threads,
        logger
      )
      f(server)
    }
    // format: off
    finally {
      if (server != null)
        server.shutdown()
    }
    // format: on
  }
}
