package bloop.bloop4j.api.handlers

import ch.epfl.scala.bsp4j._
import com.google.gson.{Gson, JsonElement}

import scala.collection.mutable.ListBuffer

class BuildClientHandlers extends BuildClient {
  private[this] val gson: Gson = new Gson()

  private[this] val showMessages: ListBuffer[ShowMessageParams] = ListBuffer.empty
  val logMessages: ListBuffer[LogMessageParams] = ListBuffer.empty
  val diagnostics: ListBuffer[PublishDiagnosticsParams] = ListBuffer.empty
  val compileReports: ListBuffer[CompileReport] = ListBuffer.empty
  val testReports: ListBuffer[TestReport] = ListBuffer.empty
  val taskStarts: ListBuffer[TaskStartParams] = ListBuffer.empty
  val taskProgresses: ListBuffer[TaskProgressParams] = ListBuffer.empty
  val taskFinishes: ListBuffer[TaskFinishParams] = ListBuffer.empty

  def reset(): Unit = {
    showMessages.clear()
    logMessages.clear()
    diagnostics.clear()
    compileReports.clear()
  }

  override def onBuildShowMessage(params: ShowMessageParams): Unit = {
    showMessages += params
  }

  override def onBuildLogMessage(params: LogMessageParams): Unit = {
    logMessages += params
  }

  override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = {
    diagnostics += params
  }

  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = {}

  override def onBuildTaskStart(params: TaskStartParams): Unit = {
    taskStarts += params
  }

  override def onBuildTaskProgress(params: TaskProgressParams): Unit = {
    taskProgresses += params
  }

  override def onBuildTaskFinish(params: TaskFinishParams): Unit = {
    taskFinishes += params

    params.getDataKind match {
      case TaskDataKind.COMPILE_REPORT =>
        val json = params.getData.asInstanceOf[JsonElement]
        val report = gson.fromJson[CompileReport](json, classOf[CompileReport])
        compileReports += report
      case TaskDataKind.TEST_REPORT =>
        val json = params.getData.asInstanceOf[JsonElement]
        val report = gson.fromJson[TestReport](json, classOf[TestReport])
        testReports += report
      case _ =>
    }
  }
}
