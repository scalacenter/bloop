package bloop.rifle.internal

import bloop.rifle.{
  BloopRifleConfig,
  BloopRifleLogger,
  BspConnection,
  BspConnectionAddress,
  FailedToStartServerException
}
import libdaemonjvm.LockFiles
import snailgun.protocol.Streams
import snailgun.{Client, TcpClient}

import java.io.{File, InputStream, OutputStream}
import java.net.{
  ConnectException,
  InetSocketAddress,
  Socket,
  StandardProtocolFamily,
  UnixDomainSocketAddress
}
import java.nio.channels.SocketChannel
import java.nio.charset.Charset
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ExecutorService, ScheduledExecutorService, ScheduledFuture}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Properties, Success, Try}

object Operations {

  private def lockFiles(address: BloopRifleConfig.Address.DomainSocket): LockFiles = {
    val path = address.path
    if (!Files.exists(path)) {
      // FIXME Small change of race condition here between createDirectories and setPosixFilePermissions
      Files.createDirectories(path)
      if (!Properties.isWin)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }
    LockFiles.under(path)
  }

  /** Checks whether a bloop server is running at this host / port.
    *
    * @param host
    * @param port
    * @param logger
    * @return
    *   Whether a server is running or not.
    */
  def check(
    address: BloopRifleConfig.Address,
    logger: BloopRifleLogger
  ): Boolean = {
    logger.debug(s"Checking for a running Bloop server at ${address.render} ...")
    address match {
      case BloopRifleConfig.Address.Tcp(host, port) =>
        // inspired by https://github.com/scalacenter/bloop/blob/cbddb8baaf639a4e08ee630f1ebc559dc70255a8/bloopgun/src/main/scala/bloop/bloopgun/core/Shell.scala#L174-L202
        Util.withSocket { socket =>
          socket.setReuseAddress(true)
          socket.setTcpNoDelay(true)
          logger.debug(s"Attempting to connect to Bloop server ${address.render} ...")
          val res =
            try {
              socket.connect(new InetSocketAddress(host, port))
              socket.isConnected()
            }
            catch {
              case _: ConnectException => false
            }
          logger.debug(s"Connection attempt result: $res")
          res
        }
      case addr: BloopRifleConfig.Address.DomainSocket =>
        val files = lockFiles(addr)
        logger.debug(s"Attempting to connect to Bloop server ${address.render} ...")
        val res = libdaemonjvm.client.Connect.tryConnect(files)
        logger.debug(s"Connection attempt result: $res")
        res match {
          case Some(Right(e)) => e.close()
          case _              =>
        }
        res.exists(_.isRight)
    }
  }

  private lazy val canCallEnvSh = {

    def findInPath(app: String): Option[Path] = {
      def pathEntries =
        Option(System.getenv("PATH"))
          .iterator
          .flatMap(_.split(File.pathSeparator).iterator)
      def matches = for {
        dir <- pathEntries
        path = Paths.get(dir).resolve(app)
        if Files.isExecutable(path)
      } yield path
      matches.take(1).toList.headOption
    }

    val env       = Paths.get("/usr/bin/env")
    val envExists = Files.exists(env)

    envExists && findInPath("sh").nonEmpty
  }

  /** Starts a new bloop server.
    *
    * @param host
    * @param port
    * @param javaPath
    * @param classPath
    * @param scheduler
    * @param waitInterval
    * @param timeout
    * @param logger
    * @return
    *   A future, that gets completed when the server is done starting (and can thus be used).
    */
  def startServer(
    address: BloopRifleConfig.Address,
    javaPath: String,
    javaOpts: Seq[String],
    classPath: Seq[Path],
    workingDir: File,
    scheduler: ScheduledExecutorService,
    waitInterval: FiniteDuration,
    timeout: Duration,
    logger: BloopRifleLogger,
    bloopServerSupportsFileTruncating: Boolean
  ): Future[Unit] = {

    val (addressArgs, mainClass, writeOutputToOpt) = address match {
      case BloopRifleConfig.Address.Tcp(host, port) =>
        (Seq(host, port.toString), "bloop.Server", None)
      case s: BloopRifleConfig.Address.DomainSocket =>
        val writeOutputToOpt0 =
          if (bloopServerSupportsFileTruncating) Some(s.outputPath)
          else None
        (Seq(s"daemon:${s.path}"), "bloop.Bloop", writeOutputToOpt0)
    }

    val extraJavaOpts =
      writeOutputToOpt.toSeq.map { writeOutputTo =>
        s"-Dbloop.truncate-output-file-periodically=${writeOutputTo.toAbsolutePath}"
      }

    val baseCommand =
      Seq(javaPath) ++
        extraJavaOpts ++
        javaOpts ++
        Seq(
          "-cp",
          classPath.map(_.toString).mkString(File.pathSeparator),
          mainClass
        ) ++
        addressArgs

    val (b, cleanUp) = writeOutputToOpt match {
      case Some(writeOutputTo) if !Properties.isWin && canCallEnvSh =>
        // Using a shell script, rather than relying on ProcessBuilder#redirectErrorStream(true).
        // The shell script seems to handle better the redirection of stdout and stderr to a single file
        // (opening a single file descriptor under-the-hood, and using dup2 I assume).
        // If using a shell script ends up being a problem, we could try to mimic what it does ourselves
        // (likely from JNI, or JNA + GraalVM API, using open / dup2 / fork / exec / …)

        val script = {
          def escape(arg: String): String =
            "'" + arg.replace("'", "\\'") + "'"
          val redirectOutput = (logger.bloopCliInheritStdout, logger.bloopCliInheritStderr) match {
            case (true, true)   => ""
            case (true, false)  => s" 2> ${escape(writeOutputTo.toString)}"
            case (false, true)  => s" > ${escape(writeOutputTo.toString)}"
            case (false, false) => s" > ${escape(writeOutputTo.toString)} 2>&1"
          }
          s"exec ${baseCommand.map(escape).mkString(" ")} < /dev/null" +
            redirectOutput +
            System.lineSeparator()
        }

        val f = Files.createTempFile("start-bloop", ".sh")
        f.toFile.deleteOnExit()
        Files.write(f, script.getBytes(Charset.defaultCharset()))

        val b = new ProcessBuilder("/usr/bin/env", "sh", f.toString)
          .redirectOutput(ProcessBuilder.Redirect.INHERIT)
          .redirectError(ProcessBuilder.Redirect.INHERIT)

        val cleanUp = () =>
          try Files.delete(f)
          catch {
            case NonFatal(e) =>
              logger.debug(s"Error while deleting temp file $f", e)
          }

        (b, cleanUp)

      case Some(writeOutputTo) =>
        // FIXME If !Properties.isWin (so we have !canCallEnvSh), we're on a Unix,
        // so we could do what sh does ourselves with system calls (see comment above),
        // if ever that's needed.

        val b = new ProcessBuilder(baseCommand: _*)

        if (logger.bloopCliInheritStdout)
          b.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        else
          b.redirectOutput(writeOutputTo.toFile)

        if (logger.bloopCliInheritStderr)
          b.redirectError(ProcessBuilder.Redirect.INHERIT)
        else
          b.redirectError(writeOutputTo.toFile)

        (b, () => ())

      case None =>
        val b = new ProcessBuilder(baseCommand: _*)

        b.redirectOutput {
          if (logger.bloopCliInheritStdout) ProcessBuilder.Redirect.INHERIT
          else ProcessBuilder.Redirect.DISCARD
        }

        b.redirectError {
          if (logger.bloopCliInheritStderr) ProcessBuilder.Redirect.INHERIT
          else ProcessBuilder.Redirect.DISCARD
        }

        (b, () => ())
    }

    workingDir.mkdirs()
    b.directory(workingDir)
    b.redirectInput(ProcessBuilder.Redirect.PIPE)
    val p = b.start()
    p.getOutputStream.close()

    val promise = Promise[Unit]()

    def check0(f: => ScheduledFuture[_]): Runnable = {
      val start = System.currentTimeMillis()
      () =>
        try {
          val completionOpt =
            if (!p.isAlive())
              Some(Failure(new FailedToStartServerException(outputFileOpt = writeOutputToOpt)))
            else if (check(address, logger))
              Some(Success(()))
            else if (timeout.isFinite && System.currentTimeMillis() - start > timeout.toMillis)
              Some(Failure(new FailedToStartServerException(Some(timeout), writeOutputToOpt)))
            else
              None

          for (completion <- completionOpt) {
            promise.tryComplete(completion)
            f.cancel(false)
            cleanUp()
          }
        }
        catch {
          case t: Throwable =>
            if (timeout.isFinite && System.currentTimeMillis() - start > timeout.toMillis) {
              promise.tryFailure(t)
              f.cancel(false)
              cleanUp()
            }
            throw t
        }
    }

    lazy val f: ScheduledFuture[_] = scheduler.scheduleAtFixedRate(
      logger.runnable("bloop-server-start-check")(check0(f)),
      0L,
      waitInterval.length,
      waitInterval.unit
    )

    f

    promise.future
  }

  private def nailgunClient(address: BloopRifleConfig.Address): Client =
    address match {
      case BloopRifleConfig.Address.Tcp(host, port) =>
        TcpClient(host, port)
      case addr: BloopRifleConfig.Address.DomainSocket =>
        SnailgunClient { () =>
          val files = lockFiles(addr)
          val res   = libdaemonjvm.client.Connect.tryConnect(files)
          res match {
            case None          => ??? // not running
            case Some(Left(_)) => ??? // error
            case Some(Right(channel)) =>
              libdaemonjvm.Util.socketFromChannel(channel)
          }
        }
    }

  /** Opens a BSP connection to a running bloop server.
    *
    * Starts a thread to read output from the nailgun connection, and another one to pass input to
    * it.
    *
    * @param host
    * @param port
    * @param inOpt
    * @param out
    * @param err
    * @param logger
    * @return
    *   A [[BspConnection]] object, that can be used to close the connection.
    */
  def bsp(
    address: BloopRifleConfig.Address,
    bspSocketOrPort: BspConnectionAddress,
    workingDir: Path,
    inOpt: Option[InputStream],
    out: OutputStream,
    err: OutputStream,
    logger: BloopRifleLogger
  ): BspConnection = {

    val stop0          = new AtomicBoolean
    val nailgunClient0 = nailgunClient(address)
    val streams        = Streams(inOpt, out, err)

    val promise    = Promise[Int]()
    val threadName = "bloop-rifle-nailgun-out"
    val protocolArgs = bspSocketOrPort match {
      case t: BspConnectionAddress.Tcp =>
        Array("--protocol", "tcp", "--host", t.host, "--port", t.port.toString)
      case s: BspConnectionAddress.UnixDomainSocket =>
        Array("--protocol", "local", "--socket", s.path.getAbsolutePath)
    }
    val runnable: Runnable = logger.runnable(threadName) { () =>
      val maybeRetCode = Try {
        nailgunClient0.run(
          "bsp",
          protocolArgs,
          workingDir,
          sys.env.toMap,
          streams,
          logger.nailgunLogger,
          stop0,
          interactiveSession = false
        )
      }
      try promise.complete(maybeRetCode)
      catch { case _: IllegalStateException => }
    }

    val snailgunThread = new Thread(runnable, threadName)
    snailgunThread.setDaemon(true)

    snailgunThread.start()

    new BspConnection {
      def address = bspSocketOrPort match {
        case t: BspConnectionAddress.Tcp => s"${t.host}:${t.port}"
        case s: BspConnectionAddress.UnixDomainSocket =>
          "local:" + s.path.toURI.toASCIIString.stripPrefix("file:")
      }
      def openSocket(period: FiniteDuration, timeout: FiniteDuration) = bspSocketOrPort match {
        case t: BspConnectionAddress.Tcp =>
          new Socket(t.host, t.port)
        case s: BspConnectionAddress.UnixDomainSocket =>
          val socketFile            = s.path
          var count                 = 0
          val maxCount              = (timeout / period).toInt
          var socket: SocketChannel = null
          while (socket == null && count < maxCount && closed.value.isEmpty) {
            logger.debug {
              if (socketFile.exists())
                s"BSP connection $socketFile found but not open, waiting $period"
              else
                s"BSP connection at $socketFile not found, waiting $period"
            }
            Thread.sleep(period.toMillis)
            if (socketFile.exists()) {
              val addr = UnixDomainSocketAddress.of(socketFile.toPath)
              socket = SocketChannel.open(StandardProtocolFamily.UNIX)
              socket.connect(addr)
              socket.finishConnect()
            }
            count += 1
          }
          if (socket != null) {
            logger.debug(s"BSP connection at $socketFile opened")
            libdaemonjvm.Util.socketFromChannel(socket)
          }
          else if (closed.value.isEmpty)
            sys.error(s"Timeout while waiting for BSP socket to be created in $socketFile")
          else
            sys.error(
              s"Bloop BSP connection in $socketFile was unexpectedly closed or bloop didn't start."
            )
      }
      val closed = promise.future
      def stop() = stop0.set(true)
    }
  }

  def exit(
    address: BloopRifleConfig.Address,
    workingDir: Path,
    out: OutputStream,
    err: OutputStream,
    logger: BloopRifleLogger
  ): Int =
    run(
      "ng-stop",
      Array.empty,
      workingDir,
      address,
      None,
      out,
      err,
      logger
    )

  def run(
    command: String,
    args: Array[String],
    workingDir: Path,
    address: BloopRifleConfig.Address,
    inOpt: Option[InputStream],
    out: OutputStream,
    err: OutputStream,
    logger: BloopRifleLogger,
    assumeInTty: Boolean = false,
    assumeOutTty: Boolean = false,
    assumeErrTty: Boolean = false
  ): Int = {

    val stop0          = new AtomicBoolean
    val nailgunClient0 = nailgunClient(address)
    val streams = Streams(
      inOpt,
      out,
      err,
      inIsATty = if (assumeInTty) 1 else 0,
      outIsATty = if (assumeOutTty) 1 else 0,
      errIsATty = if (assumeErrTty) 1 else 0
    )

    nailgunClient0.run(
      command,
      args,
      workingDir,
      sys.env.toMap,
      streams,
      logger.nailgunLogger,
      stop0,
      interactiveSession = false
    )
  }

  def about(
    address: BloopRifleConfig.Address,
    workingDir: Path,
    out: OutputStream,
    err: OutputStream,
    logger: BloopRifleLogger,
    scheduler: ExecutorService
  ): Int = {

    val stop0          = new AtomicBoolean
    val nailgunClient0 = nailgunClient(address)
    val streams        = Streams(None, out, err)

    timeout(30.seconds, scheduler, logger) {
      nailgunClient0.run(
        "about",
        Array.empty,
        workingDir,
        sys.env.toMap,
        streams,
        logger.nailgunLogger,
        stop0,
        interactiveSession = false
      )
    }

  }

  def timeout[T](
    duration: Duration,
    scheduler: ExecutorService,
    logger: BloopRifleLogger
  )(body: => T) = {
    val p = Promise[T]()
    scheduler.execute { () =>
      try {
        val retCode = body
        p.tryComplete(Success(retCode))
      }
      catch {
        case t: Throwable =>
          logger.debug(s"Caught $t while trying to run code with timeout")
          p.tryComplete(Failure(t))
      }
    }

    Await.result(p.future, duration)
  }
}
