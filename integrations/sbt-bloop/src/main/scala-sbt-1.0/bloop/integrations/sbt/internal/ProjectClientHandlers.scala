package bloop.integrations.sbt.internal

import bloop.integrations.sbt.BloopCompileInputs

import bloop.bloop4j.BloopCompileReport
import bloop.bloop4j.api.handlers.BuildClientHandlers

import ch.epfl.scala.bsp4j.TaskStartParams
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.CompileTask

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.{Future => JFuture}

import xsbti.compile.AnalysisContents
import scala.util.control.NonFatal
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import java.util.concurrent.ExecutorService

final class ProjectClientHandlers(
    inputs: BloopCompileInputs,
    analysisMap: ConcurrentHashMap[BuildTargetIdentifier, JFuture[Option[AnalysisContents]]],
    executor: ExecutorService
) {
  private[this] val logger = inputs.logger
  private[this] val reporter = inputs.reporter
  def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = {
    val source = params.getTextDocument()
    params.getDiagnostics().forEach { diagnostic =>
      ProjectClientReporter.report(reporter, diagnostic, source)
    }
  }

  def onBuildCompileStart(params: TaskStartParams, compileTask: CompileTask): Unit = {
    val msg = params.getMessage()
    if (!msg.startsWith("Start no-op compilation for")) {
      logger.info(msg)
    }
  }

  def onBuildCompileFinish(params: TaskFinishParams, report: BloopCompileReport): Unit = {
    def readAndStoreAnalysis(analysisOut: File): Option[AnalysisContents] = {
      try {
        val store = ProjectUtils.bloopStaticCacheStore(analysisOut)
        if (!analysisOut.exists()) None
        else store.readFromDisk
      } catch {
        case NonFatal(t) =>
          logger.error(s"Fatal error when reading analysis from ${analysisOut}!")
          logger.trace(t)
          None
      }
    }

    val target = report.getTarget()
    val requestId = report.getOriginId()
    val analysisOut = report.getAnalysisOut()
    reporter.printSummary()

    // Analysis out is only present after successful
    if (analysisOut != null) {
      val analysisOutFile = new File(new java.net.URI(analysisOut))
      val futureAnalysis = executor.submit(() => readAndStoreAnalysis(analysisOutFile))
      analysisMap.put(target, futureAnalysis)
    }

    ()
  }
}
