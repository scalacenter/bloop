package bloop.engine.tasks

import bloop.Project
import bloop.cli.ExitStatus
import bloop.engine.State
import bloop.exec.ProcessConfig

import sbt.internal.inc.Analysis
import xsbt.api.Discovery

object RunTasks {
  def run(state: State, project: Project, className: String, args: Array[String]): State = {
    val classpath = project.classesDir +: project.classpath
    val processConfig = ProcessConfig(project.javaEnv, classpath)
    val exitCode = processConfig.runMain(className, args, state.logger)
    val exitStatus = {
      if (exitCode == ProcessConfig.EXIT_OK) ExitStatus.Ok
      else ExitStatus.UnexpectedError
    }

    state.mergeStatus(exitStatus)
  }

  def findMainClasses(state: State, project: Project): Array[String] = {
    import state.logger
    import bloop.util.JavaCompat.EnrichOptional
    val analysis = state.results.getResult(project).analysis().toOption match {
      case Some(analysis: Analysis) => analysis
      case _ =>
        logger.warn(s"`Run` is triggered but no compilation detected from '${project.name}'.")
        sbt.internal.inc.Analysis.empty
    }

    val mainClasses = analysis.infos.allInfos.values.flatMap(_.getMainClasses)
    logger.debug(s"Found ${mainClasses.size} main classes${mainClasses.mkString(": ", ", ", ".")}")
    mainClasses.toArray
  }
}
