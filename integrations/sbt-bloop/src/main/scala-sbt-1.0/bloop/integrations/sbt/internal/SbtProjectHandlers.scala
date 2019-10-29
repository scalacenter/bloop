package bloop.integrations.sbt.internal

import bloop.integrations.sbt.BloopCompileInputs

import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.TaskStartParams
import ch.epfl.scala.bsp4j.CompileTask
import ch.epfl.scala.bsp4j.BloopCompileReport
import ch.epfl.scala.bsp4j.TaskFinishParams
import java.io.File
import xsbti.compile.AnalysisContents
import scala.util.control.NonFatal
import java.util.concurrent.ConcurrentHashMap
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import java.util.concurrent.{Future => JFuture}

final class SbtProjectHandlers(inputs: BloopCompileInputs) extends ProjectHandlers {
  import inputs.{logger, reporter}
  def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = {
    params.getDiagnostics().forEach { diagnostic =>
      SbtBspReporter.report(diagnostic, params.getTextDocument(), reporter)
    }
  }

  def onBuildCompileStart(params: TaskStartParams, compileTask: CompileTask): Unit = {
    val msg = params.getMessage()
    if (!msg.startsWith("Start no-op compilation for")) {
      logger.info(params.getMessage())
    }
  }

  def onBuildCompileFinish(params: TaskFinishParams, report: BloopCompileReport): Unit = {
    def readAndStoreAnalysis(analysisOut: File): Option[AnalysisContents] = {
      try {
        val store = Utils.bloopStaticCacheStore(analysisOut)
        if (!analysisOut.exists()) None
        else store.readFromDisk
      } catch {
        case NonFatal(t) =>
          logger.error(s"Fatal error when reading analysis from ${analysisOut}")
          logger.trace(t)
          None
      }
    }

    val target = report.getTarget()
    val requestId = report.getOriginId()
    val analysisOut = new File(new java.net.URI(report.getAnalysisOut()))
    val futureAnalysis = SbtBspClient.executor.submit(() => readAndStoreAnalysis(analysisOut))
    val analysisMap = SbtBspClient.compileAnalysisMapPerRequest.computeIfAbsent(
      requestId,
      (_: String) => {
        val initial =
          new ConcurrentHashMap[BuildTargetIdentifier, JFuture[Option[AnalysisContents]]]()
        initial.put(target, futureAnalysis)
        initial
      }
    )
    analysisMap.put(target, futureAnalysis)
    ()
  }
}
