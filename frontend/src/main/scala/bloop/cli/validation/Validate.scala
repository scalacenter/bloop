package bloop.cli.validation

import java.nio.charset.Charset
import java.nio.file.{Files, Path}

import bloop.cli.{BspProtocol, Commands, CommonOptions, ExitStatus}
import bloop.engine.{Action, Exit, Print, Run}
import bloop.io.AbsolutePath
import bloop.util.OS

object Validate {
  private def cliError(msg: String, commonOptions: CommonOptions): Action =
    Print(msg, commonOptions, Exit(ExitStatus.InvalidCommandLineOption))

  // https://github.com/scalacenter/bloop/issues/196
  private final val DefaultCharset = Charset.defaultCharset()
  private[bloop] def bytesOf(s: String): Int = s.getBytes(DefaultCharset).length
  private final val PipeName = "^\\Q\\\\.\\pipe\\\\E(.*)".r
  def bsp(cmd: Commands.Bsp, isWindows: Boolean): Action = {
    val cliOptions = cmd.cliOptions
    val commonOptions = cliOptions.common

    def validateSocket = cmd.socket.map(_.toAbsolutePath) match {
      case Some(socket) if Files.exists(socket) =>
        cliError(Feedback.existingSocketFile(socket), commonOptions)
      case Some(socket) if !Files.exists(socket.getParent) =>
        cliError(Feedback.missingParentOfSocket(socket), commonOptions)
      case Some(socket) if OS.isMac && bytesOf(socket.toString) > 104 =>
        cliError(Feedback.excessiveSocketLengthInMac(socket), commonOptions)
      case Some(socket) if bytesOf(socket.toString) > 108 =>
        cliError(Feedback.excessiveSocketLength(socket), commonOptions)
      case Some(socket) => Run(Commands.UnixLocalBsp(AbsolutePath(socket), cliOptions))
      case None => cliError(Feedback.MissingSocket, commonOptions)
    }

    def validatePipeName = cmd.pipeName match {
      case Some(p @ PipeName(_)) => Run(Commands.WindowsLocalBsp(p, cliOptions))
      case Some(wrong) => cliError(Feedback.unexpectedPipeFormat(wrong), commonOptions)
      case None => cliError(Feedback.MissingPipeName, commonOptions)
    }

    def validateTcp = {
      import java.net.{InetAddress, UnknownHostException}
      def continueValidation(address: InetAddress): Action = cmd.port match {
        case n if n > 0 && n <= 1023 => cliError(Feedback.reservedPortNumber(n), commonOptions)
        case n if n > 1023 && n <= 65535 => Run(Commands.TcpBsp(address, n, cliOptions))
        case invalid => cliError(Feedback.outOfRangePort(invalid), commonOptions)
      }

      scala.util.control.Exception
        .catching(classOf[UnknownHostException])
        .either { continueValidation(InetAddress.getByName(cmd.host)) } match {
        case Right(action) => action
        case Left(_) => cliError(Feedback.unknownHostName(cmd.host), commonOptions)
      }
    }

    cmd.protocol match {
      case BspProtocol.Local if isWindows => validatePipeName
      case BspProtocol.Local => validateSocket
      case BspProtocol.Tcp => validateTcp
    }
  }
}

object Feedback {
  val MissingPipeName = "Missing pipe name to establish a local connection in Windows"
  val MissingSocket =
    "A socket file is required to establish a local connection through Unix sockets"
  def excessiveSocketLengthInMac(socket: Path): String =
    s"The length of the socket path '${socket.toString}' exceeds 104 bytes in macOS"
  def excessiveSocketLength(socket: Path): String =
    s"The length of the socket path '${socket.toString}' exceeds 108 bytes"
  def existingSocketFile(socket: Path): String =
    s"Bloop bsp server cannot establish a connection with an existing socket file '${socket.toAbsolutePath}'"
  def missingParentOfSocket(socket: Path): String =
    s"'${socket.toAbsolutePath}' cannot be created because its parent does not exist"
  def unexpectedPipeFormat(pipeName: String): String =
    s"Pipe name '${pipeName}' does not start with '\\\\.\\pipe\\'"
  def outOfRangePort(n: Int): String =
    s"Port number '${n}' is either negative or bigger than 65535"
  def reservedPortNumber(n: Int): String =
    s"Port number '${n}' is reserved for the operating system. Use a port number bigger than 1024"
  def unknownHostName(host: String): String =
    s"Host name '$host' could not be either parsed or resolved"
}
