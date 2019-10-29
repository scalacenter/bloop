package bloop.integrations.sbt.internal

import bloop.bloop4j.api.handlers.BuildClientHandlers
import ch.epfl.scala.bsp4j.BloopCompileReport
import ch.epfl.scala.bsp4j.TaskStartParams
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.CompileTask

abstract class ProjectHandlers {
  def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit
  def onBuildCompileStart(params: TaskStartParams, compileTask: CompileTask): Unit
  def onBuildCompileFinish(params: TaskFinishParams, report: BloopCompileReport): Unit
}
