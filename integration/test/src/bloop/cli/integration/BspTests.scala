package bloop.cli.integration

import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer

class BspTests extends munit.FunSuite {

  test("no JSON junk in errors") {
    TmpDir.fromTmpDir { root =>
      val dirArgs = Seq[os.Shellable]("--daemon-dir", root / "daemon")
      val bspFile = root / "bsp-socket"

      val dummyMsg =
        """{
          |  "jsonrpc": "2.0",
          |  "method": "workspace/buildTargetz",
          |  "params": null,
          |  "id": 2
          |}""".stripMargin

      var bspProc: os.SubProcess = null
      var socket: SocketChannel  = null

      try {
        os.proc(Launcher.launcher, dirArgs, "about")
          .call(cwd = root, stdin = os.Inherit, stdout = os.Inherit, env = Launcher.extraEnv)

        bspProc =
          os.proc(Launcher.launcher, dirArgs, "bsp", "--protocol", "local", "--socket", bspFile)
            .spawn(cwd = root, stdin = os.Inherit, stdout = os.Inherit)

        val addr         = UnixDomainSocketAddress.of(bspFile.toNIO)
        var connected    = false
        var attemptCount = 0

        while (!connected && bspProc.isAlive() && attemptCount < 10) {
          if (attemptCount > 0)
            Thread.sleep(1000L)
          attemptCount += 1

          if (os.exists(bspFile)) {
            socket = SocketChannel.open(StandardProtocolFamily.UNIX)
            socket.connect(addr)
            socket.finishConnect()
            connected = true
          }
        }

        if (!connected)
          sys.error("Not connected to Bloop server via BSP :|")

        def sendMsg(msg: String): Unit = {
          val bytes = msg.getBytes(StandardCharsets.UTF_8)

          def doWrite(buf: ByteBuffer): Unit =
            if (buf.position() < buf.limit()) {
              val written = socket.write(buf)
              if (written == 0)
                Thread.sleep(100L)
              doWrite(buf)
            }

          doWrite(ByteBuffer.wrap(
            (s"Content-Length: ${bytes.length}" + "\r\n\r\n").getBytes(StandardCharsets.UTF_8)
          ))
          doWrite(ByteBuffer.wrap(bytes))
        }

        sendMsg(dummyMsg)

        val arr = Array.ofDim[Byte](10 * 1024)
        val buf = ByteBuffer.wrap(arr)

        // seems to do the job…
        def doRead(buf: ByteBuffer, count: Int): Int = {
          val read = socket.read(buf)
          if (read <= 0 && count < 100) {
            Thread.sleep(100L)
            doRead(buf, count + 1)
          }
          else
            read
        }

        val read = doRead(buf, 0)
        assert(read > 0)

        val resp = new String(arr, 0, read, StandardCharsets.UTF_8)

        def validateJson(content: String): Boolean = {
          assert(content.startsWith("{"))
          assert(content.endsWith("}"))
          val chars    = content.toCharArray
          val objCount = Array.ofDim[Int](chars.length)
          for (i <- 0 until chars.length) {
            val previous = if (i == 0) 0 else objCount(i - 1)
            val count    = previous + (if (chars(i) == '{') 1 else if (chars(i) == '}') -1 else 0)
            objCount(i) = count
          }
          objCount.dropRight(1).forall(_ > 0)
        }

        resp.linesWithSeparators.toVector match {
          case Seq(cl, empty, other @ _*)
              if cl.startsWith("Content-Length:") && empty.trim.isEmpty =>
            val json      = other.mkString
            val validated = validateJson(json)
            if (!validated)
              pprint.err.log(json)
            assert(validated, "Unexpected JSON response shape")
          case _ =>
            pprint.err.log(resp)
            sys.error("Unexpected response shape")
        }
      }
      finally {
        if (socket != null)
          socket.close()

        if (bspProc != null) {
          bspProc.waitFor(1000L)
          if (bspProc.isAlive())
            bspProc.close()

          os.proc(Launcher.launcher, dirArgs, "exit")
            .call(cwd = root, stdin = os.Inherit, stdout = os.Inherit, check = false)
        }
      }
    }
  }

}
