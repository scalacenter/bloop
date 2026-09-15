package bloop.io

import java.net.BindException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files

import scala.util.Failure
import scala.util.Success
import scala.util.Try

object ServerHandleSpec extends bloop.testing.BaseSuite {

  private def testNonWindows(name: String)(fun: => Any): Unit = {
    if (isWindows) ignore(name, "DISABLED")(fun) else test(name)(fun)
  }

  private def withSocketPath(f: AbsolutePath => Unit): Unit = {
    val tempDir = Files.createTempDirectory("sockets")
    try f(AbsolutePath(tempDir.resolve("test.socket")))
    finally Paths.delete(AbsolutePath(tempDir))
  }

  /** Binds and closes a channel, which leaves the socket file behind as a dead server would. */
  private def leaveStaleSocketFile(socketFile: AbsolutePath): Unit = {
    val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    try channel.bind(UnixDomainSocketAddress.of(socketFile.syntax))
    finally channel.close()
    assert(Files.exists(socketFile.underlying))
  }

  private def assertBindFails(socketFile: AbsolutePath): Unit = {
    Try(ServerHandle.UnixLocal(socketFile).server) match {
      case Failure(_: BindException) => ()
      case Failure(t) => fail(s"Expected a BindException, got $t")
      case Success(server) =>
        server.close()
        fail(s"Expected binding ${socketFile.syntax} to fail, but it succeeded")
    }
  }

  testNonWindows("bind over a socket file left behind by a dead server") {
    withSocketPath { socketFile =>
      leaveStaleSocketFile(socketFile)

      val handle = ServerHandle.UnixLocal(socketFile)
      try assert(Files.exists(socketFile.underlying))
      finally handle.server.close()
    }
  }

  testNonWindows("refuse to bind over a socket file owned by a live server") {
    withSocketPath { socketFile =>
      val live = ServerHandle.UnixLocal(socketFile)
      try {
        assertBindFails(socketFile)
        // The live server must keep owning its address
        assert(Files.exists(socketFile.underlying))
      } finally live.server.close()
    }
  }

  testNonWindows("leave a regular file sitting at the socket path untouched") {
    withSocketPath { socketFile =>
      Files.write(socketFile.underlying, "not a socket".getBytes("UTF-8"))

      assertBindFails(socketFile)
      assert(Files.exists(socketFile.underlying))
    }
  }
}
