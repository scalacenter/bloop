package bloop.dap

import com.microsoft.java.debug.core.protocol.Events.DebugEvent
import com.microsoft.java.debug.core.protocol.JsonUtils.{fromJson, toJson, toJsonTree}
import com.microsoft.java.debug.core.protocol.Messages
import monix.eval.Task
import monix.execution.atomic.Atomic

import scala.reflect.{ClassTag, classTag}
import scala.util.{Failure, Try}

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
    def first(implicit proxy: DebugSessionProxy): Task[A] = {
      proxy.events.channel(name).first.flatMap(parse)
    }

    def all(implicit proxy: DebugSessionProxy): Task[Seq[A]] = {
      proxy.events.channel(name).all.flatMap(parse)
    }

    private def parse(event: Messages.Event): Task[A] = {
      val parsed = DebugTestProtocol.parse[A](event.body)
      Task.fromTry(parsed)
    }

    private def parse(events: Seq[Messages.Event]): Task[Seq[A]] = {
      Task.sequence(events.map(parse))
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
