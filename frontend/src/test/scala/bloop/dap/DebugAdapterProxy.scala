package bloop.dap

import java.net.{Socket, SocketException}
import java.nio.ByteBuffer

import bloop.bsp.BloopLanguageClient
import bloop.dap.DebugTestProtocol.Response
import com.microsoft.java.debug.core.protocol.Messages.ProtocolMessage
import com.microsoft.java.debug.core.protocol.{JsonUtils, Messages}
import monix.eval.Task
import monix.execution.{Ack, Scheduler}
import monix.reactive.observables.ObservableLike.Operator
import monix.reactive.observers.Subscriber
import monix.reactive.{Observable, Observer}

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.meta.jsonrpc.{BaseProtocolMessage, MessageWriter}
import monix.reactive.MulticastStrategy
import bloop.engine.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import com.microsoft.java.debug.core.protocol.Events.DebugEvent
import java.util.concurrent.ConcurrentHashMap
import com.microsoft.java.debug.core.protocol.Events

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
      .headL
      .map {
        case (event, idx) =>
          lastSeenIndex = idx
          event
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
    val in = BaseProtocolMessage
      .fromInputStream(socket.getInputStream, null)
      .liftByOperator(Parser)

    val out = new Writer(BloopLanguageClient.fromOutputStream(socket.getOutputStream, null))

    new DebugAdapterProxy(in, out)
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
