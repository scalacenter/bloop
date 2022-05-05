package bloop.cli.completion

import bloop.cli.BspProtocol
import bloop.cli.ReporterKind
import bloop.data.Project

import caseapp.core.Arg
import caseapp.core.help.CommandHelp

/** Format for tab completion with fish */
object FishFormat extends Format {

  override def showProject(project: Project): Some[String] = {
    Some(project.name)
  }

  override def showCommand(name: String, messages: CommandHelp): Some[String] = {
    Some(name)
  }

  override def showArg(commandName: String, arg: Arg): Option[String] = {
    val completionFn = completionFunction(commandName, arg.name.name)
    val name = arg.name
    arg.helpMessage.map { help0 =>
      val help = arg.helpMessage.fold("")("'" + _.message + "'")
      val argDesc = if (arg.isFlag) "(_boolean)" else completionFn
      s"${name.name}#$help#$argDesc"
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

  private def completionFunction(cmdName: String, argName: String): String =
    (cmdName, argName) match {
      case (_, "project") => "(_projects)"
      case (_, "configDir") => "(_files)"
      case (_, "reporter") => "(_reporters)"
      case ("bsp", "protocol") => "(_protocols)"
      case ("test", "filter") => "(_testsfqcn)"
      case ("run", "main") => "(_mainsfqcn)"
      case _ => ""
    }
}
