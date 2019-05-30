package bloop.dap

import com.microsoft.java.debug.core.protocol.Events.DebugEvent
import com.microsoft.java.debug.core.protocol.JsonUtils.{fromJson, toJson, toJsonTree}
import com.microsoft.java.debug.core.protocol.Messages
import monix.eval.Task
import monix.execution.atomic.Atomic
import monix.execution.{Ack, Scheduler}
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

import scala.concurrent.Future
import scala.reflect.{ClassTag, classTag}
import scala.util.{Failure, Success, Try}

private[dap] object DebugTestProtocol {
  private val id = Atomic(1)
  private def nextId: Int = id.incrementAndGet()

  final class Request[A, +B: ClassTag](name: String) {
    def apply(arg: A)(implicit proxy: DebugSessionProxy): Task[B] = {
      val message = createRequest(arg)
      proxy.request(message).flatMap(parseResponse)
    }

    private def createRequest(argument: A): Messages.Request = {
      val json = toJsonTree(argument, argument.getClass).getAsJsonObject
      new Messages.Request(nextId, name, json)
    }

    private def parseResponse(response: Messages.Response): Task[B] =
      Task.fromTry(parse(response))

    private def parse(response: Messages.Response): Try[B] = {
      if (response.command == name) {
        Try {
          val targetType = classTag[B].runtimeClass.asInstanceOf[Class[B]]
          if (targetType == classOf[Unit]) {
            ().asInstanceOf[B] // ignore body
          } else {
            val json = toJson(response.body)
            fromJson(json, targetType)
          }
        }
      } else {
        val error = s"Unexpected response command. Expected [$name], got [${response.command}]"
        Failure(new IllegalStateException(error))
      }
    }
  }

  final class Event[A <: DebugEvent: ClassTag](val name: String) {
    def first(implicit proxy: DebugSessionProxy): Task[A] =
      all.firstL

    def before(event: Event[_])(implicit proxy: DebugSessionProxy): Observable[A] =
      all.takeWhile(e => e.`type` != event.name)

    def all(implicit proxy: DebugSessionProxy): Observable[A] = {
      proxy.events
        .filter(e => e.event == name)
        .liftByOperator(
          (subscriber: Subscriber[A]) =>
            new Subscriber[Messages.Event] {
              override def onNext(elem: Messages.Event): Future[Ack] = {
                parse[A](elem.body) match {
                  case Failure(ex) =>
                    subscriber.onError(ex)
                    Ack.Stop
                  case Success(event) =>
                    println(s"Event: ${event.`type`}")
                    subscriber.onNext(event)
                }
              }
              override implicit def scheduler: Scheduler = subscriber.scheduler
              override def onError(ex: Throwable): Unit = subscriber.onError(ex)
              override def onComplete(): Unit = subscriber.onComplete()
            }
        )
    }
  }

  private def parse[A: ClassTag](body: AnyRef): Try[A] =
    Try {
      val targetType = classTag[A].runtimeClass.asInstanceOf[Class[A]]
      if (targetType == classOf[Unit]) {
        ().asInstanceOf[A] // ignore body
      } else {
        val json = toJson(body)
        fromJson(json, targetType)
      }
    }
}
