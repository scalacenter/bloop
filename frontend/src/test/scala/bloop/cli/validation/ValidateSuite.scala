package bloop.cli.validation

import java.nio.file.Files
import java.util.Locale

import bloop.Cli
import bloop.cli._
import bloop.engine.{Action, Exit, Print, Run}
import bloop.util.OS
import org.junit.{Assert, Test}

class ValidateSuite {
  val tempDir = Files.createTempDirectory("validate")
  tempDir.toFile.deleteOnExit()

  @Test def FailAtWrongEndingPipeName(): Unit = {
    checkInvalidPipeName(s"\\\\.\\pie\\test-$uniqueId")
  }

  @Test def FailAtWrongMiddlePipeName(): Unit = {
    checkInvalidPipeName(s"\\,\\pipe\\test-$uniqueId")
  }

  @Test def FailAtWrongStartingPipeName(): Unit = {
    checkInvalidPipeName(s"\\.\\pipe\\test-$uniqueId")
  }

  @Test def FailAtCommonWrongPipeName(): Unit = {
    checkInvalidPipeName("test-pipe-name")
  }

  @Test def FailAtExistingSocket(): Unit = {
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

  @Test def SucceedAtNonExistingSocketRelativeFile(): Unit = {
    // Don't specify the parent on purpose, simulate relative paths from CLI
    val socketPath = java.nio.file.Paths.get("test.socket")
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = Some(socketPath),
      pipeName = None
    )

    checkIsCommand[Commands.UnixLocalBsp](Validate.bsp(bspCommand, isWindows = false))
  }

  @Test def FailAtNonExistingSocketFolder(): Unit = {
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

  @Test def FailAtLengthySocket(): Unit = {
    // See http://www.cs.utah.edu/plt/popl16/doc/unix-socket/index.html
    val tempBytes = Validate.bytesOf(tempDir.toString)
    val limit = if (OS.isMac) 104 else 108
    val missing = limit - tempBytes
    val lengthyName = "a" * missing

    val socketPath = tempDir.resolve(s"$lengthyName")
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = Some(socketPath),
      pipeName = None
    )

    val msg =
      if (OS.isMac) Feedback.excessiveSocketLengthInMac(socketPath)
      else Feedback.excessiveSocketLength(socketPath)
    checkIsCliError(Validate.bsp(bspCommand, isWindows = false), msg)
  }

  @Test def FailAtMissingSocket(): Unit = {
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

  @Test def FailAtMissingPipeName(): Unit = {
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

  @Test def SucceedAtCorrectPipeNameFromCli(): Unit = {
    val pipeName = s"\\\\.\\pipe\\test-$uniqueId"
    val bspCommand = Commands.WindowsLocalBsp(
      pipeName = pipeName,
      cliOptions = CliOptions.default
    )

    val oldProperty = System.getProperty("os.name").toLowerCase(Locale.ENGLISH)
    try {
      System.setProperty("os.name", "windows")
      val args = Array("bsp", "--protocol", "local", "--pipe-name", pipeName)
      Assert.assertEquals(
        Run(bspCommand),
        Cli.parse(args, CommonOptions.default)
      )
    } finally {
      System.setProperty("os.name", oldProperty)
      ()
    }
  }

  @Test def SucceedAtCorrectPipeName(): Unit = {
    val pipeName = s"\\\\.\\pipe\\test-$uniqueId"
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = None,
      pipeName = Some(pipeName)
    )

    checkIsCommand[Commands.WindowsLocalBsp](Validate.bsp(bspCommand, isWindows = true))
  }

  @Test def SucceedAtNonExistingSocket(): Unit = {
    val socketPath = tempDir.resolve("alsjkdflkjasdf.socket")
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = Some(socketPath),
      pipeName = None
    )

    checkIsCommand[Commands.UnixLocalBsp](Validate.bsp(bspCommand, isWindows = false))
  }

  @Test def SucceedAtDefaultTcpOptions(): Unit = {
    val bspCommand = Commands.Bsp(protocol = BspProtocol.Tcp)
    checkIsCommand[Commands.TcpBsp](Validate.bsp(bspCommand, isWindows = false))
  }

  @Test def SucceedAtCustomTcpOptions(): Unit = {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Tcp,
      host = "localhost",
      port = 5001
    )

    checkIsCommand[Commands.TcpBsp](Validate.bsp(bspCommand, isWindows = false))
  }

  @Test def FailAtNonsensicalHostAddress(): Unit = {
    checkInvalidAddress("localhos")
  }

  @Test def FailAtInvalidIpv4HostAddresses(): Unit = {
    checkInvalidAddress("127.1.1.1.1")
    checkInvalidAddress("1278.1.1.1.1")
  }

  @Test def FailAtInvalidIpv6HostAddress(): Unit = {
    checkInvalidAddress("0.1000.0.0.0.0.0.0")
  }

  @Test def SuccessAtValidIpv4Addresses(): Unit = {
    checkValidAddress("142.123.1.1")
    checkValidAddress("92.13.8.0")
  }

  @Test def SuccessAtValidIpv6Addresses(): Unit = {
    checkValidAddress("::1")
    checkValidAddress("::")
  }

  @Test def SuccessAtValidPort(): Unit = {
    checkValidPort(4333)
  }

  @Test def FailAtOutOfRangePort(): Unit = {
    checkOutOfRangePort(0)
    checkOutOfRangePort(Integer.MIN_VALUE)
    checkOutOfRangePort(Integer.MAX_VALUE)
  }

  @Test def FailAtReservedPorts(): Unit = {
    checkReservedPort(1023)
    checkReservedPort(1)
    checkReservedPort(127)
    checkReservedPort(21)
    checkReservedPort(23)
  }

  def uniqueId = java.util.UUID.randomUUID().toString.take(8)
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
