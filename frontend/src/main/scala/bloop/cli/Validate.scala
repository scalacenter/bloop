package bloop.cli

import java.nio.charset.Charset
import java.nio.file.Files

import bloop.bsp.BspServer
import bloop.io.AbsolutePath
import bloop.util.CrossPlatform
import bloop.engine.{Action, Exit, Feedback, Print, Run, State}

import monix.eval.Task

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
      case Some(socket) if CrossPlatform.isMac && bytesOf(socket.toString) > 104 =>
        cliError(Feedback.excessiveSocketLengthInMac(socket), commonOptions)
      case Some(socket) if bytesOf(socket.toString) > 108 =>
        cliError(Feedback.excessiveSocketLength(socket), commonOptions)
      case Some(socket) => Run(Commands.UnixLocalBsp(AbsolutePath(socket), cliOptions))
      case None => cliError(Feedback.MissingSocket, commonOptions)
    }

    def validatePipeName = cmd.pipeName match {
      case Some(pipeName @ PipeName(_)) => Run(Commands.WindowsLocalBsp(pipeName, cliOptions))
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

  /**
   * Reports errors related to the build definition.
   *
   * The method is responsible of handling non-existing .bloop configuration directories
   * and the reporting of recursive traces. This method is called from two places: the
   * interpreter and [[bloop.bsp.BloopBspServices]], where we invoke this method whenever
   * the user is importing the build.
   *
   * @param state The state we want to validate.
   * @param report A function for reporting (in BSP we send a showMessage, otherwise we print).
   * @return A state, possibly with a status code if there were errors.
   */
  def validateBuildForCLICommands(state: State, report: String => Unit): Task[State] = {
    val configDirectory = state.build.origin
    if (state.build.origin.isDirectory) {
      state.build.traces match {
        case Nil => Task.now(state)
        case x :: xs =>
          Task.now {
            xs.foldLeft(report(Feedback.reportRecursiveTrace(x))) {
              case (state, trace) => report(Feedback.reportRecursiveTrace(trace))
            }

            state.mergeStatus(ExitStatus.BuildDefinitionError)
          }
      }
    } else {
      Task.now {
        report(Feedback.missingConfigDirectory(configDirectory))
        state.mergeStatus(ExitStatus.BuildDefinitionError)
      }
    }
  }
}
