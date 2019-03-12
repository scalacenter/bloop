package bloop.bsp

import bloop.engine.State
import bloop.config.Config
import bloop.cli.{ExitStatus, BspProtocol}
import bloop.util.{TestUtil, TestProject}
import bloop.logging.RecordingLogger
import bloop.internal.build.BuildInfo

object TcpBspProtocolSpec extends ModernBspProtocolSpec(BspProtocol.Tcp)
object LocalBspProtocolSpec extends ModernBspProtocolSpec(BspProtocol.Local)

class ModernBspProtocolSpec(override val protocol: BspProtocol) extends BspBaseSuite {
  test("check the correct contents of scalac options") {}
}
