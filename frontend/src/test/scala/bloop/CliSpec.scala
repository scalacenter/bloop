package bloop

import java.nio.file.Files

import bloop.cli.{BspProtocol, Commands, ExitStatus, Validate}
import bloop.engine.{Action, Exit, Feedback, Print, Run}
import bloop.util.UUIDUtil
import org.junit.Test
import bloop.util.TestUtil
import bloop.testing.BaseSuite

object CliSpec extends BaseSuite {
  val tempDir = Files.createTempDirectory("validate")
  tempDir.toFile.deleteOnExit()

  test("fail at wrong end of pipe name") {
    checkInvalidPipeName(s"\\\\.\\pie\\test-$uniqueId")
  }

  test("fail at wrong middle part of pipe name") {
    checkInvalidPipeName(s"\\,\\pipe\\test-$uniqueId")
  }

  test("fail at wrong start of pipe name") {
    checkInvalidPipeName(s"\\.\\pipe\\test-$uniqueId")
  }

  test("fail at common wrong pipe name") {
    checkInvalidPipeName("test-pipe-name")
  }

  test("fail at existing socket") {
    val socketPath = tempDir.resolve("test.socket")
    Files.createFile(socketPath)
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = Some(socketPath),
      pipeName = None
    )

    checkIsCliError(
      Validate.bsp(bspCommand, isWindows = false),
      Feedback.existingSocketFile(socketPath)
    )
  }

  test("succeed at non-existing relative file for socket ") {
    // Don't specify the parent on purpose, simulate relative paths from CLI
    val socketPath = java.nio.file.Paths.get("test.socket")
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = Some(socketPath),
      pipeName = None
    )

    checkIsCommand[Commands.UnixLocalBsp](Validate.bsp(bspCommand, isWindows = false))
  }

  test("fail at non-existing socket folder") {
    val socketPath = tempDir.resolve("folder").resolve("test.socket")
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = Some(socketPath),
      pipeName = None
    )

    checkIsCliError(
      Validate.bsp(bspCommand, isWindows = false),
      Feedback.missingParentOfSocket(socketPath)
    )
  }

  test("fail at socket lengthy name") {
    // See http://www.cs.utah.edu/plt/popl16/doc/unix-socket/index.html
    val tempBytes = Validate.bytesOf(tempDir.toString)
    val limit = if (bloop.util.CrossPlatform.isMac) 104 else 108
    val missing = limit - tempBytes
    val lengthyName = "a" * missing

    val socketPath = tempDir.resolve(s"$lengthyName")
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = Some(socketPath),
      pipeName = None
    )

    val msg =
      if (bloop.util.CrossPlatform.isMac) Feedback.excessiveSocketLengthInMac(socketPath)
      else Feedback.excessiveSocketLength(socketPath)
    checkIsCliError(Validate.bsp(bspCommand, isWindows = false), msg)
  }

  test("fail at missing socket") {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = None,
      pipeName = None
    )

    checkIsCliError(
      Validate.bsp(bspCommand, isWindows = false),
      Feedback.MissingSocket
    )
  }

  test("fail at missing pipe name") {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = None,
      pipeName = None
    )

    checkIsCliError(
      Validate.bsp(bspCommand, isWindows = true),
      Feedback.MissingPipeName
    )
  }

  test("succeed at correct pipe name") {
    val pipeName = s"\\\\.\\pipe\\test-$uniqueId"
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = None,
      pipeName = Some(pipeName)
    )

    checkIsCommand[Commands.WindowsLocalBsp](Validate.bsp(bspCommand, isWindows = true))
  }

  test("succeed at non-existing socket file") {
    val socketPath = tempDir.resolve("alsjkdflkjasdf.socket")
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = Some(socketPath),
      pipeName = None
    )

    checkIsCommand[Commands.UnixLocalBsp](Validate.bsp(bspCommand, isWindows = false))
  }

  test("succeed at default tcp options") {
    val bspCommand = Commands.Bsp(protocol = BspProtocol.Tcp)
    checkIsCommand[Commands.TcpBsp](Validate.bsp(bspCommand, isWindows = false))
  }

  test("succeed at custom tcp options") {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Tcp,
      host = "localhost",
      port = 5001
    )

    checkIsCommand[Commands.TcpBsp](Validate.bsp(bspCommand, isWindows = false))
  }

  test("fail at non-sensical host address") {
    checkInvalidAddress("localhost-")
  }

  test("fail at invalid ipv4 host addresses") {
    checkInvalidAddress("127.1.1.1.1")
    checkInvalidAddress("1278.1.1.1.1")
  }

  test("fail at invalid ipv6 host address") {
    checkInvalidAddress("0.1000.0.0.0.0.0.0")
  }

  test("success at valid ipv4 addresses") {
    checkValidAddress("142.123.1.1")
    checkValidAddress("92.13.8.0")
  }

  test("succeed at valid ipv6 addresses") {
    checkValidAddress("::1")
    checkValidAddress("::")
  }

  test("success at valid port number") {
    checkValidPort(4333)
  }

  test("fail at out of range port number") {
    checkOutOfRangePort(0)
    checkOutOfRangePort(Integer.MIN_VALUE)
    checkOutOfRangePort(Integer.MAX_VALUE)
  }

  test("fail at reserved port numbers") {
    checkReservedPort(1023)
    checkReservedPort(1)
    checkReservedPort(127)
    checkReservedPort(21)
    checkReservedPort(23)
  }

  def uniqueId = UUIDUtil.randomUUID.take(8)
  def checkIsCliError(action: Action, expected: String): Unit = {
    action match {
      case Print(obtained, _, Exit(code))
          if expected == obtained && code == ExitStatus.InvalidCommandLineOption =>
        ()
      case _ =>
        throw new AssertionError(
          s"Expected msg ${expected} and exit status ${ExitStatus.InvalidCommandLineOption} in ${action}."
        )
    }
  }

  import scala.reflect.ClassTag
  def checkIsCommand[T <: Commands.Command: ClassTag](action: Action): Unit = {
    action match {
      case Run(obtained: T, Exit(ExitStatus.Ok)) => ()
      case _ => throw new AssertionError(s"Expected command doesn't exist in ${action}.")
    }
  }

  def checkInvalidPipeName(pipeName: String): Unit = {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = None,
      pipeName = Some(pipeName)
    )

    checkIsCliError(
      Validate.bsp(bspCommand, isWindows = true),
      Feedback.unexpectedPipeFormat(pipeName)
    )
  }

  def checkInvalidAddress(hostName: String): Unit = {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Tcp,
      host = hostName
    )

    checkIsCliError(
      Validate.bsp(bspCommand, isWindows = false),
      expected = Feedback.unknownHostName(hostName)
    )
  }

  def checkValidAddress(hostName: String): Unit = {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Tcp,
      host = hostName
    )

    checkIsCommand[Commands.TcpBsp](Validate.bsp(bspCommand, isWindows = false))
  }

  def checkValidPort(portNumber: Int): Unit = {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Tcp,
      port = portNumber
    )

    checkIsCommand[Commands.TcpBsp](Validate.bsp(bspCommand, isWindows = false))
  }

  def checkOutOfRangePort(portNumber: Int): Unit = {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Tcp,
      port = portNumber
    )

    checkIsCliError(
      Validate.bsp(bspCommand, isWindows = false),
      expected = Feedback.outOfRangePort(portNumber)
    )
  }

  def checkReservedPort(portNumber: Int): Unit = {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Tcp,
      port = portNumber
    )

    checkIsCliError(
      Validate.bsp(bspCommand, isWindows = false),
      expected = Feedback.reservedPortNumber(portNumber)
    )
  }
}
