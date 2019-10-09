package bloop.dap

import com.microsoft.java.debug.core.protocol.Events.DebugEvent
import com.microsoft.java.debug.core.protocol.JsonUtils.{fromJson, toJson, toJsonTree}
import com.microsoft.java.debug.core.protocol.Messages
import monix.eval.Task
import monix.execution.atomic.Atomic

import scala.reflect.{ClassTag, classTag}
import scala.util.Try

private[dap] object DebugTestProtocol {
  sealed trait Response[+A]
  object Response {
    final case class Success[A](result: A) extends Response[A]
    final case class Failure(message: String) extends Response[Nothing]
  }

  private val id = Atomic(1)
  private def nextId: Int = id.incrementAndGet()

  final class Request[A, +B: ClassTag](name: String) {
    def serialize(argument: A): Messages.Request = {
      val json = toJsonTree(argument, argument.getClass).getAsJsonObject
      new Messages.Request(nextId, name, json)
    }

    def deserialize(response: Messages.Response): Task[B] = {
      if (response.command == name) {
        Task {
          if (response.success) {
            val targetType = classTag[B].runtimeClass.asInstanceOf[Class[B]]
            if (targetType == classOf[Unit]) {
              ().asInstanceOf[B] // ignore body
            } else {
              val json = toJson(response.body)
              fromJson(json, targetType)
            }
          } else {
            throw new Exception(response.message)
          }
        }
      } else {
        val error = s"Unexpected response command. Expected [$name], got [${response.command}]"
        Task.raiseError(new IllegalStateException(error))
      }
    }
  }

  final class Event[A <: DebugEvent: ClassTag](val name: String) {
    def rawDeserialize(event: Messages.Event): Try[A] = {
      DebugTestProtocol.deserialize[A](event.body)
    }

    def deserialize(event: Messages.Event): Task[A] = {
      Task.fromTry(rawDeserialize(event))
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
