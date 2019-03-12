package bloop.bsp

import bloop.engine.State
import bloop.config.Config
import bloop.cli.{ExitStatus, BspProtocol}
import bloop.util.{TestUtil, TestProject}
import bloop.logging.RecordingLogger
import bloop.internal.build.BuildInfo

object TcpBspProtocolSpec extends ModernBspProtocolSpec(BspProtocol.Tcp, true)
object LocalBspProtocolSpec extends ModernBspProtocolSpec(BspProtocol.Local, false)

class ModernBspProtocolSpec(
    override val protocol: BspProtocol,
    override val runOnWindows: Boolean
) extends BspBaseSuite {
  test("check the correct contents of scalac options") {}
}
