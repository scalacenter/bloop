package bloop.bsp

import bloop.cli.BspProtocol

object LocalBspSbtClientSpec extends BspSbtClientSpec(BspProtocol.Local)
object TcpBspSbtClientSpec extends BspSbtClientSpec(BspProtocol.Tcp)

class BspSbtClientSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {
  test("supports compilation from sbt") { () }
}
