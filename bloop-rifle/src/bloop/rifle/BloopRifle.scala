package bloop.rifle

import bloop.rifle.internal.{Operations, Util}

import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.Future

object BloopRifle {

  /** Checks whether a bloop server is running at this host / port.
    *
    * @param host
    * @param port
    * @param logger
    * @return
    *   Whether a server is running or not.
    */
  def check(
    config: BloopRifleConfig,
    logger: BloopRifleLogger
  ): Boolean = {
    def check() =
      Operations.check(
        config.address,
        logger
      )
    check()
  }

  /** Starts a new bloop server.
    *
    * @param config
    * @param scheduler
    * @param logger
    * @return
    *   A future, that gets completed when the server is done starting (and can thus be used).
    */
  def startServer(
    config: BloopRifleConfig,
    scheduler: ScheduledExecutorService,
    logger: BloopRifleLogger,
    version: String,
    bloopJava: String
  ): Future[Unit] =
    config.classPath(version) match {
      case Left(ex) => Future.failed(new Exception("Error getting Bloop class path", ex))
      case Right((cp, isScalaCliBloop)) =>
        logger.info("Starting compilation server")
        logger.debug(
          s"Starting Bloop $version at ${config.address.render} using JVM $bloopJava"
        )
        object IntValue {
          def unapply(s: String): Option[Int] =
            // no String.toIntOption in Scala 2.12.x
            try Some(s.toInt)
            catch {
              case _: NumberFormatException => None
            }
        }
        val bloopServerSupportsFileTruncating =
          isScalaCliBloop && {
            version.takeWhile(c => c.isDigit || c == '.').split('.') match {
              case Array(IntValue(maj), IntValue(min), IntValue(patch), _ @_*) =>
                import scala.math.Ordering.Implicits._
                Seq(maj, min, patch) >= Seq(1, 4, 20)
              case _ =>
                false
            }
          }
        Operations.startServer(
          config.address,
          bloopJava,
          config.javaOpts,
          cp.map(_.toPath),
          config.workingDir,
          scheduler,
          config.startCheckPeriod,
          config.startCheckTimeout,
          logger,
          bloopServerSupportsFileTruncating = bloopServerSupportsFileTruncating
        )
    }

  /** Opens a BSP connection to a running bloop server.
    *
    * Starts a thread to read output from the nailgun connection, and another one to pass input to
    * it.
    *
    * @param logger
    * @return
    *   A [[BspConnection]] object, that can be used to close the connection.
    */
  def bsp(
    config: BloopRifleConfig,
    workingDir: Path,
    logger: BloopRifleLogger
  ): BspConnection = {

    val bspSocketOrPort = config.bspSocketOrPort.map(_()).getOrElse {
      BspConnectionAddress.Tcp("127.0.0.1", Util.randomPort())
    }

    val inOpt = config.bspStdin

    val out = config.bspStdout.getOrElse(OutputStream.nullOutputStream())
    val err = config.bspStderr.getOrElse(OutputStream.nullOutputStream())

    Operations.bsp(
      config.address,
      bspSocketOrPort,
      workingDir,
      inOpt,
      out,
      err,
      logger
    )
  }

  def exit(
    config: BloopRifleConfig,
    workingDir: Path,
    logger: BloopRifleLogger
  ): Int = {

    val out = config.bspStdout.getOrElse(OutputStream.nullOutputStream())
    val err = config.bspStderr.getOrElse(OutputStream.nullOutputStream())

    Operations.exit(
      config.address,
      workingDir,
      out,
      err,
      logger
    )
  }

  def getCurrentBloopVersion(
    config: BloopRifleConfig,
    logger: BloopRifleLogger,
    workdir: Path,
    scheduler: ScheduledExecutorService
  ): Either[BloopAboutFailure, BloopServerRuntimeInfo] = {
    val isRunning = BloopRifle.check(config, logger)

    if (isRunning) {
      val bufferedOStream = new ByteArrayOutputStream
      Operations.about(
        config.address,
        workdir,
        bufferedOStream,
        OutputStream.nullOutputStream(),
        logger,
        scheduler
      )
      val bloopAboutOutput = new String(bufferedOStream.toByteArray)
      VersionUtil.parseBloopAbout(bloopAboutOutput)
        .toRight(ParsingFailed(bloopAboutOutput))
    }
    else
      Left(BloopNotRunning)
  }

  sealed abstract class BloopAboutFailure extends Product with Serializable {
    def message: String
  }
  case object BloopNotRunning extends BloopAboutFailure {
    def message = "not running"
  }
  final case class ParsingFailed(bloopAboutOutput: String) extends BloopAboutFailure {
    def message = s"failed to parse output: '$bloopAboutOutput'"
  }

  final case class BloopServerRuntimeInfo(
    bloopVersion: BloopVersion,
    jvmVersion: Int,
    javaHome: String
  ) {
    def message: String =
      s"version $bloopVersion, JVM $jvmVersion under $javaHome"
  }
}
