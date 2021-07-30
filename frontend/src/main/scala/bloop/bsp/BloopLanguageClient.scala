package bloop.bsp

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels

import monix.execution.Ack
import monix.reactive.Observer
import scribe.LoggerSupport

object BloopLanguageClient {

  /**
   * An observer implementation that writes messages to the underlying output
   * stream. This class is copied over from lsp4s but has been modified to
   * synchronize writing on the output stream. Synchronizing makes sure BSP
   * clients see server responses in the order they were sent.
   *
   * If this is a bottleneck in the future, we can consider removing the
   * synchronized blocks here and in the body of `BloopLanguageClient` and
   * replace them with a ring buffer and an id generator to make sure all
   * server interactions are sent out in order. As it's not a performance
   * blocker for now, we opt for the synchronized approach.
   */
  def fromOutputStream(
      out: OutputStream,
      logger: LoggerSupport
  ): Observer.Sync[ByteBuffer] = {
    new Observer.Sync[ByteBuffer] {
      private[this] var isClosed: Boolean = false
      private[this] val channel = Channels.newChannel(out)
      override def onNext(elem: ByteBuffer): Ack = out.synchronized {
        if (isClosed) Ack.Stop
        else {
          try {
            channel.write(elem)
            out.flush()
            Ack.Continue
          } catch {
            case t: java.io.IOException =>
              logger.trace("OutputStream closed!", t)
              isClosed = true
              Ack.Stop
          }
        }
      }
      override def onError(ex: Throwable): Unit = ()
      override def onComplete(): Unit = {
        out.synchronized {
          channel.close()
          out.close()
        }
      }
    }
  }
}
