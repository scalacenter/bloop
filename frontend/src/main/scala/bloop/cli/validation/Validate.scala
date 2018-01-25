package bloop.cli.validation

import java.nio.file.{Files, Path}

import bloop.bsp.BspServer
import bloop.cli.{BspProtocol, Commands, CommonOptions, ExitStatus}
import bloop.engine.{Action, Exit, Print, Run}

object Validate {
  private def cliError(msg: String, commonOptions: CommonOptions): Action =
    Print(msg, commonOptions, Exit(ExitStatus.InvalidCommandLineOption))

  def bsp(cmd: Commands.Bsp): Action = {
    def validateSocket = cmd.socket match {
      case Some(socket) if Files.exists(socket) =>
        cliError(Feedback.existingSocketFile(socket), cmd.cliOptions.common)
      case Some(socket) if !Files.exists(socket.getParent) =>
        cliError(Feedback.missingParentOf(socket), cmd.cliOptions.common)
      case Some(socket) => Run(cmd, Exit(ExitStatus.Ok))
      case None =>
        cliError(Feedback.MissingSocket, cmd.cliOptions.common)
    }

    cmd.protocol match {
      case BspProtocol.Local if BspServer.isWindows =>
        if (cmd.pipeName.isDefined) Run(cmd, Exit(ExitStatus.Ok))
        else cliError(Feedback.MissingPipeName, cmd.cliOptions.common)
      case BspProtocol.Local => validateSocket
      case BspProtocol.Tcp => Run(cmd, Exit(ExitStatus.Ok))
    }
  }
}

object Feedback {
  val MissingPipeName = "Missing pipe name to establish a local connection in Windows."
  val MissingSocket =
    "A socket file is required to establish a local connection through Unix sockets."
  def existingSocketFile(socket: Path): String =
    s"Bloop bsp server cannot establish a connection with an existing socket file '${socket.toAbsolutePath}'."
  def missingParentOf(socket: Path): String =
    s"'${socket.toAbsolutePath}' cannot be created because its parent doesn't exist."
}
