package bloop.bsp

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import bloop.util.monix.FoldLeftAsyncConsumer

import jsonrpc4s._
import monix.eval.Task
import monix.execution.CancelableFuture
import monix.execution.Scheduler
import monix.reactive.Observable
import scribe.LoggerSupport

final class BloopLanguageServer(
    in: Observable[Message],
    client: RpcClient,
    services: Services,
    requestScheduler: Scheduler,
    logger: LoggerSupport
) {
  private val activeClientRequests: TrieMap[RequestId, CancelableFuture[Response]] = TrieMap.empty
  private val cancelNotification =
    Service.notification(RpcActions.cancelRequest, logger) {
      new Service[CancelParams, Unit] {
        def handle(params: CancelParams): Task[Unit] = {
          val id = params.id
          activeClientRequests.get(id) match {
            case None =>
              Task {
                logger.warn(s"Can't cancel request $id, no active request found.")
                () // Response.empty
              }
            case Some(request) =>
              Task {
                logger.info(s"Cancelling request $id")
                request.cancel()
                () // Response.cancelled(id)
              }
          }
        }
      }
    }

  private val handlersByMethodName: Map[String, NamedJsonRpcService] =
    services.addService(cancelNotification).byMethodName

  def cancelAllRequests(): Unit = {
    activeClientRequests.values.foreach { cancelable =>
      cancelable.cancel()
    }
  }

  def awaitRunningTasks: Task[Unit] = {
    val futures = activeClientRequests.values.map(fut => Task.fromFuture(fut))
    // Await until completion and ignore task results
    Task.parSequenceUnordered(futures).materialize.map(_ => ())
  }

  def handleResponse(response: Response): Task[Response] = Task {
      client.clientRespond(response)
      Response.None
  }

  def handleNotification(notification: Notification): Task[Response] = {
    handlersByMethodName.get(notification.method) match {
      case None =>
        Task.eval {
          // Can't respond to invalid notifications
          logger.error(s"Unknown method '${notification.method}'")
          Response.None
        }
      case Some(handler) =>
        handler
          .handle(notification)
          .onErrorRecover {
            case NonFatal(e) =>
              logger.error(s"Error handling notification $notification", e)
              Response.None
          }
          .map {
          case Response.None => Response.None
          case nonEmpty =>
            logger.error(s"Obtained non-empty response $nonEmpty for notification $notification!")
            Response.None
        }
    }
  }

def handleRequest(request: Request): Task[Response] = {
    import request.{method, id}
    handlersByMethodName.get(method) match {
      case None =>
        Task.eval {
          logger.info(s"Method not found '$method'")
          Response.methodNotFound(method, id)
        }

      case Some(handler) =>
        val response = handler.handle(request).onErrorRecover {
          case NonFatal(e) =>
            logger.error(s"Unhandled JSON-RPC error handling request $request", e)
            Response.internalError(e.getMessage, request.id)
        }
        val runningResponse = response.runToFuture(requestScheduler)
        activeClientRequests.put(request.id, runningResponse)
        Task.fromFuture(runningResponse)
    }
  }

  def handleValidMessage(message: Message): Task[Response] = message match {
    case response: Response => handleResponse(response)
    case notification: Notification => handleNotification(notification)
    case request: Request => handleRequest(request)
  }

  def startTask: Task[Unit] = {
    in.foreachL { msg =>
      handleValidMessage(msg)
        .map(client.serverRespond)
        .onErrorRecover { case NonFatal(e) => logger.error("Unhandled error", e) }
        .executeWithOptions(_.disableAutoCancelableRunLoops).runAsync(requestScheduler)
      ()
    }
  }

  def processMessagesSequentiallyTask: Task[Unit] = {
    in.consumeWith(FoldLeftAsyncConsumer.consume[Unit, Message](()) {
      case (_, msg) =>
        handleValidMessage(msg)
          .map(client.serverRespond)
          .onErrorRecover { case NonFatal(e) => logger.error("Unhandled error", e) }
          .map(_ => ())
    })
  }
}
