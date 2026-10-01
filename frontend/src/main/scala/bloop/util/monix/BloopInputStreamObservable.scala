package bloop.util.monix

import java.io.InputStream
import java.net.SocketException
import java.util.Arrays

import scala.concurrent.blocking
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

import monix.execution.Ack
import monix.execution.Ack.Continue
import monix.execution.Ack.Stop
import monix.execution.Cancelable
import monix.execution.ExecutionModel
import monix.execution.Scheduler
import monix.execution.cancelables.BooleanCancelable
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

object BloopInputStreamObservable {
  private final val DefaultChunkSize = 4096

  /**
   * Reads and emits each chunk returned by the input stream without waiting for the buffer to fill.
   *
   * This works around https://github.com/monix/monix/issues/1479, which otherwise causes
   * interactive streams such as BSP and DAP sockets to wait indefinitely for a full chunk.
   */
  def apply(in: InputStream, chunkSize: Int = DefaultChunkSize): Observable[Array[Byte]] = {
    require(chunkSize > 0, "chunkSize must be strictly positive")
    new InputStreamObservable(in, chunkSize)
  }

  private final class InputStreamObservable(in: InputStream, chunkSize: Int)
      extends Observable[Array[Byte]] {
    override def unsafeSubscribeFn(out: Subscriber[Array[Byte]]): Cancelable = {
      val cancelable = BooleanCancelable()
      reschedule(
        Continue,
        new Array[Byte](chunkSize),
        out,
        cancelable,
        out.scheduler.executionModel
      )(
        out.scheduler
      )
      cancelable
    }

    private def reschedule(
        ack: Future[Ack],
        buffer: Array[Byte],
        out: Subscriber[Array[Byte]],
        cancelable: BooleanCancelable,
        executionModel: ExecutionModel
    )(implicit scheduler: Scheduler): Unit = {
      ack.onComplete {
        case Success(Continue) if !cancelable.isCanceled =>
          readLoop(buffer, out, cancelable, executionModel, 0)
        case Success(_) => ()
        case Failure(error) => scheduler.reportFailure(error)
      }
    }

    private def readLoop(
        buffer: Array[Byte],
        out: Subscriber[Array[Byte]],
        cancelable: BooleanCancelable,
        executionModel: ExecutionModel,
        syncIndex: Int
    )(implicit scheduler: Scheduler): Unit = {
      val ack =
        try {
          val length = blocking(in.read(buffer))
          if (cancelable.isCanceled) Stop
          else if (length < 0) {
            out.onComplete()
            Stop
          } else {
            out.onNext(Arrays.copyOf(buffer, length))
          }
        } catch {
          case _: SocketException if !cancelable.isCanceled =>
            out.onComplete()
            Stop
          case NonFatal(_) if cancelable.isCanceled =>
            Stop
          case NonFatal(error) =>
            out.onError(error)
            Stop
        }

      val nextIndex =
        if (ack == Continue) executionModel.nextFrameIndex(syncIndex)
        else if (ack == Stop) -1
        else 0

      if (nextIndex > 0 && !cancelable.isCanceled)
        readLoop(buffer, out, cancelable, executionModel, nextIndex)
      else if (nextIndex == 0 && !cancelable.isCanceled)
        reschedule(ack, buffer, out, cancelable, executionModel)
    }
  }
}
