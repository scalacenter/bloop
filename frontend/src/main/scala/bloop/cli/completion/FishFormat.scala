package bloop.cli.completion

import caseapp.core.{Arg, CommandMessages}
import bloop.cli.{BspProtocol, ReporterKind}
import bloop.data.Project

/** Format for tab completion with fish */
object FishFormat extends Format {

  override def showProject(project: Project): Some[String] = {
    Some(project.name)
  }

  override def showCommand(name: String, messages: CommandMessages): Some[String] = {
    Some(name)
  }

  override def showArg(commandName: String, arg: Arg): Option[String] = {
    val completionFn = completionFunction(commandName, arg.name)
    arg match {
      case Arg(_, _ +: _, _, _, true, _, _, _) =>
        None
      case Arg(_, name +: _, _, Some(help), _, true, _, _) =>
        Some(s"${name.name}#'${help.message}'#(_boolean)")
      case Arg(_, name +: _, _, None, _, true, _, _) =>
        Some(s"${name.name}##(_boolean)")
      case Arg(_, name +: _, _, Some(help), _, _, _, _) =>
        Some(s"${name.name}#'${help.message}'#$completionFn")
      case Arg(_, name +: _, _, None, _, _, _, _) =>
        Some(s"${name.name}##$completionFn")
      case _ =>
        None
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
