package bloop.integrations.sbt.internal

import sbt.Logger

import java.util.concurrent.ConcurrentHashMap

import bloop.bloop4j.api.handlers.BuildClientHandlers

import com.google.gson.JsonElement

import ch.epfl.scala.bsp4j.LogMessageParams
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.ShowMessageParams
import ch.epfl.scala.bsp4j.TaskStartParams
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskProgressParams
import ch.epfl.scala.bsp4j.DidChangeBuildTarget
import ch.epfl.scala.bsp4j.BloopCompileReport
import ch.epfl.scala.bsp4j.CompileTask
import ch.epfl.scala.bsp4j.TaskDataKind
import ch.epfl.scala.bsp4j.MessageType
import ch.epfl.scala.bsp4j.BuildTargetIdentifier

class MultiProjectClientHandlers(
    glogger: Logger,
    projectHandlers: ConcurrentHashMap[BuildTargetIdentifier, ProjectClientHandlers]
) extends BuildClientHandlers {

  override def onBuildLogMessage(params: LogMessageParams): Unit = {
    val msg = params.getMessage()
    params.getType match {
      case MessageType.INFORMATION => glogger.info(msg)
      case MessageType.ERROR => glogger.error(msg)
      case MessageType.WARNING => glogger.warn(msg)
      case MessageType.LOG => glogger.info(msg)
    }
  }

  override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = {
    val targetId = params.getBuildTarget()
    val handlers = projectHandlers.get(targetId)
    if (handlers == null) glogger.warn(s"Missing reporter for ${targetId.getUri()}")
    else handlers.onBuildPublishDiagnostics(params)
  }

  override def onBuildShowMessage(params: ShowMessageParams): Unit = {
    val msg = params.getMessage()
    params.getType match {
      case MessageType.INFORMATION => glogger.info(msg)
      case MessageType.ERROR => glogger.error(msg)
      case MessageType.WARNING => glogger.warn(msg)
      case MessageType.LOG => glogger.info(msg)
    }
  }

  override def onBuildTaskStart(params: TaskStartParams): Unit = {
    params.getDataKind() match {
      case TaskDataKind.COMPILE_TASK =>
        parseAs(params.getData, classOf[CompileTask], glogger)
          .foreach { compileTask =>
            val targetId = compileTask.getTarget()
            val handlers = projectHandlers.get(targetId)
            if (handlers == null) glogger.warn(s"Missing handlers for ${targetId.getUri()}")
            else handlers.onBuildCompileStart(params, compileTask)
          }
      case _ => ()
    }
  }

  override def onBuildTaskFinish(params: TaskFinishParams): Unit = {
    params.getDataKind() match {
      case TaskDataKind.COMPILE_REPORT =>
        parseAs(params.getData, classOf[BloopCompileReport], glogger)
          .foreach { compileReport =>
            val targetId = compileReport.getTarget()
            val handlers = projectHandlers.get(targetId)
            if (handlers == null) glogger.warn(s"Missing handlers for ${targetId.getUri()}")
            else handlers.onBuildCompileFinish(params, compileReport)
          }
      case _ => ()
    }
  }

  override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()

  private def parseAs[T](
      obj: Object,
      clazz: Class[T],
      logger: Logger
  ): Option[T] = {
    Option(obj).flatMap { obj =>
      val json = obj.asInstanceOf[JsonElement]
      scala.util.Try(gson.fromJson[T](json, clazz)) match {
        case scala.util.Success(value) => Some(value)
        case scala.util.Failure(t) =>
          logger.error(s"Unexpected error parsing ${clazz}: ${t.getMessage}")
          logger.trace(t)
          None
      }
    }
  }
}
