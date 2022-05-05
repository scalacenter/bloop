package bloop.cli.completion

import bloop.cli.BspProtocol
import bloop.cli.ReporterKind
import bloop.data.Project

import caseapp.core.Arg
import caseapp.core.help.CommandHelp

object BashFormat extends Format {
  override def showProject(project: Project): Some[String] = {
    Some(project.name)
  }

  override def showCommand(name: String, messages: CommandHelp): Some[String] = {
    Some(name)
  }

  override def showArg(commandName: String, arg: Arg): Option[String] = {
    def completionFn = completionFunction(commandName, arg.name.name).map(" " + _).getOrElse("")
    val longName = arg.name
    arg.helpMessage map { _ =>
      s"--${longName.name}${completionFn}"
    }
  }

  override def showTestName(fqcn: String): Some[String] = {
    Some(fqcn)
  }

  override def showMainName(fqcn: String): Some[String] = {
    Some(fqcn)
  }

  override def showReporter(reporter: ReporterKind): Some[String] = {
    Some(reporter.name)
  }

  override def showProtocol(protocol: BspProtocol): Some[String] = {
    Some(protocol.name)
  }

  private def completionFunction(cmdName: String, argName: String): Option[String] =
    (cmdName, argName) match {
      case (_, "project") => Some("_projects")
      case (_, "configDir") => Some("_files")
      case (_, "reporter") => Some("_reporters")
      case ("bsp", "protocol") => Some("_protocols")
      case ("bsp", "socket") => Some("_files")
      case ("test", "filter") => Some("_testsfqcn")
      case ("run", "main") => Some("_mainsfqcn")
      case _ => None
    }

}
