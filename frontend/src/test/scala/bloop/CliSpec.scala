package bloop

import java.nio.file.Files
import java.nio.file.Path

import bloop.cli.BspProtocol
import bloop.cli.Commands
import bloop.cli.CommonOptions
import bloop.cli.ExitStatus
import bloop.cli.Validate
import bloop.engine.Action
import bloop.engine.Exit
import bloop.engine.Feedback
import bloop.engine.Print
import bloop.engine.Run
import bloop.internal.build.BuildInfo
import bloop.testing.BaseSuite
import bloop.util.UUIDUtil

object CliSpec extends BaseSuite {
  val tempDir: Path = Files.createTempDirectory("validate")
  tempDir.toFile.deleteOnExit()
  val opts: CommonOptions = CommonOptions.default

  test("fail at existing socket") {
    val socketPath = tempDir.resolve("test.socket")
    Files.createFile(socketPath)
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = Some(socketPath)
    )

    checkIsCliError(
      Validate.bsp(bspCommand),
      Feedback.existingSocketFile(socketPath)
    )
  }

  test("succeed at non-existing relative file for socket ") {
    // Don't specify the parent on purpose, simulate relative paths from CLI
    val socketPath = java.nio.file.Paths.get("test.socket")
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = Some(socketPath)
    )

    checkIsCommand[Commands.UnixLocalBsp](Validate.bsp(bspCommand))
  }

  test("fail at non-existing socket folder") {
    val socketPath = tempDir.resolve("folder").resolve("test.socket")
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = Some(socketPath)
    )

    checkIsCliError(
      Validate.bsp(bspCommand),
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
      socket = Some(socketPath)
    )

    val msg =
      if (bloop.util.CrossPlatform.isMac) Feedback.excessiveSocketLengthInMac(socketPath)
      else Feedback.excessiveSocketLength(socketPath)
    checkIsCliError(Validate.bsp(bspCommand), msg)
  }

  test("fail at missing socket") {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = None
    )

    checkIsCliError(
      Validate.bsp(bspCommand),
      Feedback.MissingSocket
    )
  }

  test("succeed at non-existing socket file") {
    val socketPath = tempDir.resolve("alsjkdflkjasdf.socket")
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = Some(socketPath)
    )

    checkIsCommand[Commands.UnixLocalBsp](Validate.bsp(bspCommand))
  }

  test("succeed at default tcp options") {
    val bspCommand = Commands.Bsp(protocol = BspProtocol.Tcp)
    checkIsCommand[Commands.TcpBsp](Validate.bsp(bspCommand))
  }

  test("succeed at custom tcp options") {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Tcp,
      host = "localhost",
      port = 5001
    )

    checkIsCommand[Commands.TcpBsp](Validate.bsp(bspCommand))
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

  test("succeed at valid --jvm-debug options") {
    assert(
      Validate
        .jvmDebug(Some(5005), jvmDebugSuspend = false, commonOptions = opts)
        .isEmpty
    )
    assert(
      Validate
        .jvmDebug(Some(5005), jvmDebugSuspend = true, commonOptions = opts)
        .isEmpty
    )
    assert(
      Validate
        .jvmDebug(None, jvmDebugSuspend = false, commonOptions = opts)
        .isEmpty
    )
    // Lower and upper bounds of the accepted range.
    assert(Validate.jvmDebug(Some(1024), jvmDebugSuspend = false, commonOptions = opts).isEmpty)
    assert(Validate.jvmDebug(Some(65535), jvmDebugSuspend = false, commonOptions = opts).isEmpty)
  }

  test("fail at out of range --jvm-debug port") {
    checkIsCliError(
      Validate
        .jvmDebug(Some(70000), jvmDebugSuspend = false, commonOptions = opts)
        .get,
      Feedback.outOfRangePort(70000)
    )
    checkIsCliError(
      Validate
        .jvmDebug(Some(-1), jvmDebugSuspend = false, commonOptions = opts)
        .get,
      Feedback.outOfRangePort(-1)
    )
    // Boundaries just outside the accepted range.
    checkIsCliError(
      Validate.jvmDebug(Some(0), jvmDebugSuspend = false, commonOptions = opts).get,
      Feedback.outOfRangePort(0)
    )
    checkIsCliError(
      Validate.jvmDebug(Some(65536), jvmDebugSuspend = false, commonOptions = opts).get,
      Feedback.outOfRangePort(65536)
    )
  }

  test("fail at reserved --jvm-debug port") {
    checkIsCliError(
      Validate
        .jvmDebug(Some(80), jvmDebugSuspend = false, commonOptions = opts)
        .get,
      Feedback.reservedPortNumber(80)
    )
    // Highest reserved port.
    checkIsCliError(
      Validate.jvmDebug(Some(1023), jvmDebugSuspend = false, commonOptions = opts).get,
      Feedback.reservedPortNumber(1023)
    )
  }

  test("fail at --jvm-debug-suspend without --jvm-debug") {
    checkIsCliError(
      Validate.jvmDebug(None, jvmDebugSuspend = true, commonOptions = opts).get,
      Feedback.jvmDebugSuspendWithoutPort
    )
  }

  test("parse --jvm-debug and --jvm-debug-suspend on run") {
    Cli.parse(Array("run", "foo", "--jvm-debug", "5005"), CommonOptions.default) match {
      case Run(cmd: Commands.Run, _) =>
        assert(cmd.jvmDebug == Some(5005))
        assert(!cmd.jvmDebugSuspend)
      case action => throw new AssertionError(s"Expected a run command, got $action")
    }
    Cli.parse(
      Array("run", "foo", "--jvm-debug", "5005", "--jvm-debug-suspend"),
      CommonOptions.default
    ) match {
      case Run(cmd: Commands.Run, _) =>
        assert(cmd.jvmDebug == Some(5005))
        assert(cmd.jvmDebugSuspend)
      case action => throw new AssertionError(s"Expected a run command, got $action")
    }
  }

  test("parse --jvm-debug on test") {
    Cli.parse(Array("test", "foo", "--jvm-debug", "5005"), CommonOptions.default) match {
      case Run(cmd: Commands.Test, _) =>
        assert(cmd.jvmDebug == Some(5005))
      case action => throw new AssertionError(s"Expected a test command, got $action")
    }
  }

  test("reject an out-of-range --jvm-debug port through the CLI") {
    checkIsCliError(
      Cli.parse(Array("run", "foo", "--jvm-debug", "70000"), CommonOptions.default),
      Feedback.outOfRangePort(70000)
    )
    checkIsCliError(
      Cli.parse(Array("test", "foo", "--jvm-debug", "70000"), CommonOptions.default),
      Feedback.outOfRangePort(70000)
    )
  }

  test("print about info when `--version` is passed without a command") {
    checkPrintsAbout(Cli.parse(Array("--version"), CommonOptions.default))
  }

  test("print about info when the `version` command is used") {
    checkPrintsAbout(Cli.parse(Array("version"), CommonOptions.default))
  }

  test("print about info before running a command when `--version` is passed") {
    val action = Cli.parse(Array("compile", "foo", "--version"), CommonOptions.default)
    checkPrintsAbout(
      action,
      alsoRuns = { case Run(_: Commands.Compile, Exit(ExitStatus.Ok)) => () }
    )
  }

  def checkPrintsAbout(
      action: Action,
      alsoRuns: PartialFunction[Action, Unit] = { case Exit(ExitStatus.Ok) => () }
  ): Unit = {
    action match {
      case Print(msg, _, next) if msg.contains(BuildInfo.version) && alsoRuns.isDefinedAt(next) =>
        ()
      case _ =>
        throw new AssertionError(s"Expected about info to be printed, got ${action}.")
    }
  }

  def uniqueId: String = UUIDUtil.randomUUID.take(8)
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
      case Run(_: T, Exit(ExitStatus.Ok)) => ()
      case _ => throw new AssertionError(s"Expected command doesn't exist in ${action}.")
    }
  }

  def checkInvalidAddress(hostName: String): Unit = {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Tcp,
      host = hostName
    )

    checkIsCliError(
      Validate.bsp(bspCommand),
      expected = Feedback.unknownHostName(hostName)
    )
  }

  def checkValidAddress(hostName: String): Unit = {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Tcp,
      host = hostName
    )

    checkIsCommand[Commands.TcpBsp](Validate.bsp(bspCommand))
  }

  def checkValidPort(portNumber: Int): Unit = {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Tcp,
      port = portNumber
    )

    checkIsCommand[Commands.TcpBsp](Validate.bsp(bspCommand))
  }

  def checkOutOfRangePort(portNumber: Int): Unit = {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Tcp,
      port = portNumber
    )

    checkIsCliError(
      Validate.bsp(bspCommand),
      expected = Feedback.outOfRangePort(portNumber)
    )
  }

  def checkReservedPort(portNumber: Int): Unit = {
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Tcp,
      port = portNumber
    )

    checkIsCliError(
      Validate.bsp(bspCommand),
      expected = Feedback.reservedPortNumber(portNumber)
    )
  }
}
