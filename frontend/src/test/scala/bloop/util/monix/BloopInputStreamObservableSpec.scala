package bloop.util.monix

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration._

import bloop.engine.ExecutionContext
import bloop.testing.BaseSuite

object BloopInputStreamObservableSpec extends BaseSuite {
  test("emit partial chunks without waiting for the buffer to fill") {
    val input = new PipedInputStream
    val output = new PipedOutputStream(input)
    val received = new LinkedBlockingQueue[String]()
    val running = BloopInputStreamObservable(input)
      .foreachL { bytes =>
        received.add(new String(bytes, StandardCharsets.UTF_8))
        ()
      }
      .runToFuture(ExecutionContext.ioScheduler)

    try {
      output.write("request 1".getBytes(StandardCharsets.UTF_8))
      assertEquals(received.poll(2, TimeUnit.SECONDS), "request 1")

      output.write("request 2".getBytes(StandardCharsets.UTF_8))
      assertEquals(received.poll(2, TimeUnit.SECONDS), "request 2")
    } finally {
      running.cancel()
      output.close()
      input.close()
    }
  }

  test("complete normally when a socket is closed") {
    val closedSocket = new PipedInputStream {
      override def read(bytes: Array[Byte], offset: Int, length: Int): Int =
        throw new SocketException("Socket closed")
    }

    Await.result(
      BloopInputStreamObservable(closedSocket).completedL
        .runToFuture(ExecutionContext.ioScheduler),
      2.seconds
    )
  }
}
