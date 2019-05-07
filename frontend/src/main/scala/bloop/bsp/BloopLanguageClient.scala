package bloop.bsp

import scala.meta.jsonrpc.Response
import scala.meta.jsonrpc.RequestId
import scala.meta.jsonrpc.CancelParams
import scala.meta.jsonrpc.Notification
import scala.meta.jsonrpc.JsonRpcClient
import scala.meta.jsonrpc.MessageWriter

import io.circe.Decoder
import io.circe.Encoder
import io.circe.syntax._
import java.io.OutputStream
import java.nio.ByteBuffer

import monix.eval.Task
import monix.eval.Callback
import monix.execution.Ack
import monix.reactive.Observer
import monix.execution.Cancelable
import monix.execution.atomic.Atomic
import monix.execution.atomic.AtomicInt

import scala.concurrent.Future
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration

import scribe.LoggerSupport

/**
 * A copy of `LanguageClient` defined in `lsp4s` with a few critical fixes.
 */
final class BloopLanguageClient(out: Observer[ByteBuffer], logger: LoggerSupport)
    extends JsonRpcClient {

  // ++ bloop
  def this(out: OutputStream, logger: LoggerSupport) =
    this(BloopLanguageClient.fromOutputStream(out, logger), logger)
  // -- bloop

  private val writer = new MessageWriter(out, logger)
  private val counter: AtomicInt = Atomic(1)
  private val activeServerRequests =
    TrieMap.empty[RequestId, Callback[Response]]

  def notify[A: Encoder](method: String, notification: A): Future[Ack] =
    writer.write(Notification(method, Some(notification.asJson)))

  def serverRespond(response: Response): Future[Ack] = response match {
    case Response.Empty => Ack.Continue
    case x: Response.Success => writer.write(x)
    case x: Response.Error =>
      logger.error(s"Response error: $x")
      writer.write(x)
  }

  def clientRespond(response: Response): Unit = {
    for {
      id <- response match {
        case Response.Empty => None
        case Response.Success(_, requestId) => Some(requestId)
        case Response.Error(_, requestId) => Some(requestId)
      }
      callback <- activeServerRequests.get(id).orElse {
        logger.error(s"Response to unknown request: $response")
        None
      }
    } {
      activeServerRequests.remove(id)
      callback.onSuccess(response)
    }
  }

  def request[A: Encoder, B: Decoder](
      method: String,
      request: A
  ): Task[Either[Response.Error, B]] = {
    val nextId = RequestId(counter.incrementAndGet())
    val response = Task.create[Response] { (out, cb) =>
      import java.util.concurrent.TimeUnit
      val scheduled = out.scheduleOnce(FiniteDuration(0, TimeUnit.MILLISECONDS)) {
        import scala.meta.jsonrpc.Request
        val json = Request(method, Some(request.asJson), nextId)
        activeServerRequests.put(nextId, cb)
        writer.write(json)
        ()
      }
      Cancelable { () =>
        scheduled.cancel()
        this.notify("$/cancelRequest", CancelParams(nextId.value))
        ()
      }
    }

    response.map {
      case Response.Empty =>
        Left(Response.invalidParams(s"Got empty response for request $request", nextId))
      case err: Response.Error => Left(err)
      case Response.Success(result, _) =>
        import cats.syntax.either._
        result.as[B].leftMap { err =>
          Response.invalidParams(err.toString, nextId)
        }
    }
  }

  // Addition compared to lsp4s so that our tests can request something but forget about its result
  def requestAndForget[A: Encoder](
      method: String,
      request: A
  ): Task[Unit] = {
    Task {
      val nextId = RequestId(counter.incrementAndGet())
      import scala.meta.jsonrpc.Request
      val json = Request(method, Some(request.asJson), nextId)
      writer.write(json)
      ()
    }
  }
}

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
      override def onNext(elem: ByteBuffer): Ack = out.synchronized {
        if (isClosed) Ack.Stop
        else {
          try {
            while (elem.hasRemaining) out.write(elem.get().toInt)
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
          out.close()
        }
      }
    }
  }
}
