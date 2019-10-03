package bloop.bsp

import io.circe.Json
import io.circe.jawn.parseByteBuffer
import io.circe.syntax._

import java.nio.ByteBuffer

import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.execution.CancelableFuture

import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scribe.LoggerSupport

import scala.meta.jsonrpc.Message
import scala.meta.jsonrpc.Request
import scala.meta.jsonrpc.Response
import scala.meta.jsonrpc.Service
import scala.meta.jsonrpc.Services
import scala.meta.jsonrpc.CancelParams
import scala.meta.jsonrpc.Notification
import scala.meta.jsonrpc.NamedJsonRpcService
import scala.meta.jsonrpc.BaseProtocolMessage
import bloop.util.monix.FoldLeftAsyncConsumer

final class BloopLanguageServer(
    in: Observable[BaseProtocolMessage],
    client: BloopLanguageClient,
    services: Services,
    requestScheduler: Scheduler,
    logger: LoggerSupport
) {
  private val activeClientRequests: TrieMap[Json, CancelableFuture[_]] = TrieMap.empty
  private val cancelNotification =
    Service.notification[CancelParams]("$/cancelRequest", logger) {
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
    Task.gatherUnordered(futures).materialize.map(_ => ())
  }

  def handleValidMessage(message: Message): Task[Response] = message match {
    case response: Response =>
      Task {
        client.clientRespond(response)
        Response.empty
      }
    case Notification(method, _) =>
      handlersByMethodName.get(method) match {
        case None =>
          Task {
            // Can't respond to invalid notifications
            logger.error(s"Unknown method '$method'")
            Response.empty
          }

        case Some(handler) =>
          handler
            .handle(message)
            .map {
              case Response.Empty => Response.empty
              case nonEmpty =>
                logger.error(
                  s"Obtained non-empty response $nonEmpty for notification $message. " +
                    s"Expected Response.empty"
                )
                Response.empty
            }
            .onErrorRecover {
              case NonFatal(e) =>
                logger.error(s"Error handling notification $message", e)
                Response.empty
            }
      }

    case request @ Request(method, _, id) =>
      handlersByMethodName.get(method) match {
        case None =>
          Task {
            logger.info(s"Method not found '$method'")
            Response.methodNotFound(method, id)
          }

        case Some(handler) =>
          val jsonId = request.id.asJson
          val unregisterUponCompletion = Task { activeClientRequests.remove(jsonId); () }
          val response = handler
            .handle(request)
            .doOnFinish(_ => unregisterUponCompletion)
            .onErrorRecover {
              case NonFatal(e) =>
                logger.error(s"Unhandled error handling request $request", e)
                Response.internalError(e.getMessage, request.id)
            }

          val runningResponse = response.runAsync(requestScheduler)
          activeClientRequests.put(jsonId, runningResponse)
          Task.fromFuture(runningResponse)
      }

  }

  def handleMessage(message: BaseProtocolMessage): Task[Response] =
    parseByteBuffer(ByteBuffer.wrap(message.content)) match {
      case Left(err) => Task.now(Response.parseError(err.toString))
      case Right(json) =>
        json.as[Message] match {
          case Left(err) => Task.now(Response.invalidRequest(err.toString))
          case Right(msg) => handleValidMessage(msg)
        }
    }

  def startTask: Task[Unit] = {
    in.foreachL { msg =>
      handleMessage(msg)
        .map(client.serverRespond)
        .onErrorRecover { case NonFatal(e) => logger.error("Unhandled error", e) }
        .runAsync(requestScheduler)
      ()
    }
  }

  def processMessagesSequentiallyTask: Task[Unit] = {
    in.consumeWith(FoldLeftAsyncConsumer.consume[Unit, BaseProtocolMessage](()) {
      case (_, msg) =>
        handleMessage(msg)
          .map(client.serverRespond)
          .onErrorRecover { case NonFatal(e) => logger.error("Unhandled error", e) }
          .map(_ => ())
    })
  }
}
