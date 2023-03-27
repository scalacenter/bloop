package bloop.bsp

import scala.util._

import bloop.bsp.BloopRpcServices.BloopEndpoint
import bloop.task.Task

import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import jsonrpc4s._
import scribe.LoggerSupport

/**
 * This is the similar thing as `jsonrpc4s.Services` that is used to describe a group of JSON-RPC endpoints.
 * Introduced in order to operate over `bloop.task.Task` instead of `monix.eval.Task`
 */
case class BloopRpcServices(endpoints: List[BloopEndpoint], loggerSupport: LoggerSupport) {
  def requestAsync[A, B](endpoint: Endpoint[A, B])(
      f: A => Task[Either[Response.Error, B]]
  ): BloopRpcServices =
    copy(BloopEndpoint.request(endpoint)(f) :: endpoints)

  def request[A, B](endpoint: Endpoint[A, B])(f: A => B): BloopRpcServices =
    copy(BloopEndpoint.request(endpoint)((a: A) => Task(Right(f(a)))) :: endpoints)

  def notificationAsync[A](endpoint: Endpoint[A, Unit])(f: A => Task[Unit]): BloopRpcServices =
    copy(BloopEndpoint.notification(endpoint, loggerSupport)(f) :: endpoints)

  def notification[A](endpoint: Endpoint[A, Unit])(f: A => Unit): BloopRpcServices =
    copy(BloopEndpoint.notification(endpoint, loggerSupport)((a: A) => Task(f(a))) :: endpoints)
}

object BloopRpcServices {

  def empty(loggerSupport: LoggerSupport): BloopRpcServices =
    BloopRpcServices(List.empty, loggerSupport)
  sealed trait BloopEndpoint {
    def name: String
    def handle(message: Message): Task[Response]
  }

  object BloopEndpoint {

    private def extractJsonParams(params: Option[RawJson]): RawJson = {
      // if params are empty, bsp4s endpoint's codec might decode it as just a 'null'
      // and then parsing it using `readFromArray` fails
      // to avoid this issue replace 'null' by empty object '{}'
      params
        .map(json =>
          if (java.util.Arrays.equals(json.value, RawJson.nullValue.value)) RawJson.emptyObj
          else json
        )
        .getOrElse(RawJson.emptyObj)
    }

    def request[A, B](
        endpoint: Endpoint[A, B]
    )(f: A => Task[Either[Response.Error, B]]): BloopEndpoint =
      new BloopEndpoint {
        def name: String = endpoint.method
        def handle(message: Message): Task[Response] = {
          import endpoint.{codecA, codecB}
          val method = endpoint.method
          message match {
            case Request(`method`, params, id, _, _) =>
              val paramsJson = extractJsonParams(params)
              Try(readFromArray[A](paramsJson.value)) match {
                case Success(value) =>
                  f(value).materialize.map { v =>
                    v.map(_.toTry).flatten match {
                      case Success(response) =>
                        Response.ok(RawJson.toJson(response), id)
                      // Errors always have a null id because services don't have access to the real id
                      case Failure(err: Response.Error) => err.copy(id = id)
                      case Failure(err) => Response.internalError(err, id)
                    }
                  }
                case Failure(err) => Task(Response.invalidParams(err.toString, id))
              }

            case Request(invalidMethod, _, id, _, _) =>
              Task(Response.methodNotFound(invalidMethod, id))
            case _ => Task(Response.invalidRequest(s"Expected request, obtained $message"))
          }
        }
      }

    def notification[A](endpoint: Endpoint[A, Unit], logger: LoggerSupport)(
        f: A => Task[Unit]
    ): BloopEndpoint =
      new BloopEndpoint {
        override def name: String = endpoint.method
        private def fail(msg: String): Task[Response] = Task {
          logger.error(msg)
          Response.None
        }
        override def handle(message: Message): Task[Response] = {
          import endpoint.codecA
          val method = endpoint.method
          message match {
            case Notification(`method`, params, _, _) =>
              val paramsJson = extractJsonParams(params)
              Try(readFromArray[A](paramsJson.value)) match {
                case Success(value) => f(value).map(_ => Response.None)
                case Failure(err) =>
                  fail(s"Failed to parse notification $message. Params: $paramsJson. Errors: $err")
              }
            case Notification(invalidMethod, _, _, _) =>
              fail(s"Expected method '$method', obtained '$invalidMethod'")
            case _ => fail(s"Expected notification, obtained $message")
          }
        }
      }
  }
}
