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
    def serialize(argument: A): Messages.Request = {
      val json = toJsonTree(argument, argument.getClass).getAsJsonObject
      new Messages.Request(nextId, name, json)
    }

    def deserialize(response: Messages.Response): Task[B] = {
      val deserialized = if (response.command == name) {
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

      Task.fromTry(deserialized)
    }
  }

  final class Event[A <: DebugEvent: ClassTag](val name: String) {
    def deserialize(event: Messages.Event): Task[A] = {
      val parsed = DebugTestProtocol.deserialize[A](event.body)
      Task.fromTry(parsed)
    }

    def deserialize(events: Seq[Messages.Event]): Task[Seq[A]] = {
      Task.sequence(events.map(deserialize))
    }
  }

  private def deserialize[A: ClassTag](body: AnyRef): Try[A] =
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
