package bloop.dap

import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.Promise

import bloop.bsp.BloopLanguageClient
import bloop.engine.ExecutionContext

import com.microsoft.java.debug.core.protocol.Events
import com.microsoft.java.debug.core.protocol.Events.DebugEvent
import com.microsoft.java.debug.core.protocol.JsonUtils
import com.microsoft.java.debug.core.protocol.Messages
import com.microsoft.java.debug.core.protocol.Messages.ProtocolMessage
import jsonrpc4s._
import monix.eval.Task
import monix.execution.Ack
import monix.execution.Scheduler
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import monix.reactive.Observable.Operator
import monix.reactive.Observer
import monix.reactive.observers.Subscriber

/**
 * Handles communication with the debug adapter.
 * Stores all events received from the session grouped by their type
 */
private[dap] final class DebugAdapterProxy(
    input: Observable[Messages.ProtocolMessage],
    output: Observer[Messages.ProtocolMessage]
) {
  private val outputBuf = new StringBuffer()
  private val requests = mutable.Map.empty[Int, Promise[Messages.Response]]
  private val (observer, events) =
    Observable.multicast[Messages.Event](MulticastStrategy.replay)(ExecutionContext.ioScheduler)

  events.foreach { event =>
    if (event.event == DebugTestEndpoints.OutputEvent.name) {
      DebugTestEndpoints.OutputEvent.rawDeserialize(event) match {
        case scala.util.Success(event: Events.OutputEvent) =>
          outputBuf.append(event.output)
        case _ => ()
      }
      ()
    }
  }(ExecutionContext.ioScheduler)

  def takeCurrentOutput: String = {
    outputBuf.toString()
  }

  def blockForAllOutput: Task[String] = {
    events.completedL.map { _ =>
      outputBuf.toString()
    }
  }

  private var lastSeenIndex: Long = -1
  def next[E <: DebugEvent](event: DebugTestProtocol.Event[E]): Task[E] = {
    events.zipWithIndex
      .dropWhile(_._2 <= lastSeenIndex)
      .findF(_._1.event == event.name)
      .headOptionL
      .flatMap {
        case Some((event, idx)) =>
          lastSeenIndex = idx
          Task.now(event)
        case None =>
          Task.raiseError(new NoSuchElementException(s"Missing event ${event.name}"))
      }
      .flatMap(event.deserialize(_))
  }

  def all[E <: DebugEvent](event: DebugTestProtocol.Event[E]): Task[List[E]] = {
    events
      .findF(_.event == event.name)
      .toListL
      .flatMap(events => Task.sequence(events.map(event.deserialize(_))))
  }

  def request[A, B](endpoint: DebugTestProtocol.Request[A, B], parameters: A): Task[B] = {
    val message = endpoint.serialize(parameters)
    val response = send(message)
    response.flatMap(endpoint.deserialize)
  }

  private def send(request: Messages.Request): Task[Messages.Response] = {
    val promise = Promise[Messages.Response]()
    requests += (request.seq -> promise)
    output.onNext(request)
    Task.fromFuture(promise.future)
  }

  def startBackgroundListening(scheduler: Scheduler): Unit = {
    input
      .foreachL(handleMessage)
      .runOnComplete(_ => observer.onComplete())(scheduler)
    ()
  }

  private def handleMessage(message: ProtocolMessage): Unit = {
    message match {
      case event: Messages.Event =>
        observer.onNext(event)
      case _: Messages.Request =>
        throw new IllegalStateException("Reverse requests are not supported")
      case response: Messages.Response =>
        val id = response.request_seq
        requests.get(id) match {
          case None => throw new IllegalStateException(s"Request[$id] not found")
          case Some(promise) => promise.success(response)
        }
    }
    ()
  }
}

private[dap] object DebugAdapterProxy {
  def apply(socket: Socket): DebugAdapterProxy = {
    val in = LowLevelMessage
      .fromInputStream(socket.getInputStream(), null)
      .map(msg => LowLevelMessage.toMsg(msg))
      .liftByOperator(Parser)

    val out = new Writer(BloopLanguageClient.fromOutputStream(socket.getOutputStream, null))

    new DebugAdapterProxy(in, out)
  }

  private object Parser extends Operator[Message, Messages.ProtocolMessage] {
    override def apply(
        upstream: Subscriber[Messages.ProtocolMessage]
    ): Subscriber[Message] =
      new Subscriber[Message] {
        override def onNext(elem: Message): Future[Ack] = {
          val content = new String(elem.jsonrpc)
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
      val serialized = ByteBuffer.wrap(bytes);
      underlying.onNext(serialized)
    }
    override def onError(ex: Throwable): Unit = underlying.onError(ex)
    override def onComplete(): Unit = underlying.onComplete()
  }
}
