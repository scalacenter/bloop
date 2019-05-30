package bloop.dap

import java.net.{Socket, SocketException}
import java.nio.ByteBuffer

import bloop.bsp.BloopLanguageClient
import com.microsoft.java.debug.core.protocol.Messages.ProtocolMessage
import com.microsoft.java.debug.core.protocol.{JsonUtils, Messages}
import monix.eval.Task
import monix.execution.{Ack, Scheduler}
import monix.reactive.observables.ObservableLike.Operator
import monix.reactive.observers.Subscriber
import monix.reactive.subjects.ReplaySubject
import monix.reactive.{Observable, Observer}

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.meta.jsonrpc.{BaseProtocolMessage, MessageWriter}

private[dap] final class DebugSessionProxy(
    input: Observable[Messages.ProtocolMessage],
    output: Observer[Messages.ProtocolMessage]
) {
  private val requests = mutable.Map.empty[Int, Promise[Messages.Response]]
  val events: ReplaySubject[Messages.Event] = ReplaySubject[Messages.Event]()

  def request(request: Messages.Request): Task[Messages.Response] = {
    println(s"Requesting: ${request.command}")
    val promise = Promise[Messages.Response]()
    requests += (request.seq -> promise)
    output.onNext(request)
    Task.fromFuture(promise.future)
  }

  def listen(scheduler: Scheduler): Unit = {
    input
      .foreachL(handleMessage)
      .map(_ => events.onComplete())
      .runAsync(scheduler)
  }

  private def handleMessage(message: ProtocolMessage): Unit = message match {
    case _: Messages.Request =>
      throw new IllegalStateException("Reverse requests are not supported")
    case response: Messages.Response =>
      val id = response.request_seq
      requests.get(id) match {
        case None => throw new IllegalStateException(s"Request[$id] not found")
        case Some(promise) => promise.success(response)
      }
    case event: Messages.Event =>
      events.onNext(event) // assuming always Continue
  }
}

private[dap] object DebugSessionProxy {
  def apply(socket: Socket): DebugSessionProxy = {
    val in = BaseProtocolMessage
      .fromInputStream(socket.getInputStream, null)
      .liftByOperator(Parser)

    val out = new Writer(BloopLanguageClient.fromOutputStream(socket.getOutputStream, null))

    new DebugSessionProxy(in, out)
  }

  private object Parser extends Operator[BaseProtocolMessage, Messages.ProtocolMessage] {
    override def apply(
        upstream: Subscriber[Messages.ProtocolMessage]
    ): Subscriber[BaseProtocolMessage] =
      new Subscriber[BaseProtocolMessage] {
        override def onNext(elem: BaseProtocolMessage): Future[Ack] = {
          val content = new String(elem.content)
          val messageKind = JsonUtils.fromJson(content, classOf[ProtocolMessage]).`type`
          val targetType = messageKind match {
            case "request" => classOf[Messages.Request]
            case "response" => classOf[Messages.Response]
            case "event" => classOf[Messages.Event]
          }

          upstream.onNext(JsonUtils.fromJson(content, targetType))
        }

        override implicit def scheduler: Scheduler = upstream.scheduler
        override def onError(ex: Throwable): Unit = ex match {
          case e: SocketException if e.getMessage == "Socket closed" =>
            upstream.onComplete()
          case _ =>
            upstream.onError(ex)
        }
        override def onComplete(): Unit = upstream.onComplete()
      }
  }

  private final class Writer(underlying: Observer[ByteBuffer])
      extends Observer[Messages.ProtocolMessage] {
    override def onNext(elem: ProtocolMessage): Future[Ack] = {
      val bytes = JsonUtils.toJson(elem).getBytes
      val message = BaseProtocolMessage.fromBytes(bytes)
      val serialized = MessageWriter.write(message)
      underlying.onNext(serialized)
    }
    override def onError(ex: Throwable): Unit = underlying.onError(ex)
    override def onComplete(): Unit = underlying.onComplete()
  }
}
