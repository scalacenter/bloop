package bloop.cli.completion

import caseapp.core.{Arg, CommandMessages}

import bloop.Project
import bloop.cli.{BspProtocol, ReporterKind}

/** Format for autocompletion with zsh */
object ZshFormat extends Format {

  override def showProject(project: Project): Some[String] = {
    Some(project.name)
  }

  override def showCommand(name: String, messages: CommandMessages): Some[String] = {
    Some(name)
  }

  override def showArg(commandName: String, arg: Arg): Option[String] = {
    val completionFn = completionFunction(commandName, arg.name)
    arg match {
      case Arg(_, name +: _, _, _, true, _, _, _) =>
        None
      case Arg(_, name +: _, _, Some(help), _, true, _, _) => {
        val camelName = Case.camelToKebab(name.name)
        Some(s"--${camelName}=-[${help.message}]:${camelName}:(true false)")
      }
      case Arg(_, name +: _, _, None, _, true, _, _) => {
        val camelName = Case.camelToKebab(name.name)
        Some(s"--${camelName}=-:${camelName}:(true false)")
      }
      case Arg(_, name +: _, _, Some(help), _, _, _, _) =>{
        val camelName = Case.camelToKebab(name.name)
        Some(s"--${camelName}[${help.message}]:${camelName}:${completionFn}")
      }
      case Arg(_, name +: _, _, None, _, _, _, _) =>{
        val camelName = Case.camelToKebab(name.name)
        Some(s"--${camelName}:${camelName}:${completionFn}")
      }
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
      case (_, "project") => "_projects"
      case (_, "configDir") => "_files"
      case (_, "reporter") => "_reporters"
      case ("bsp", "protocol") => "_protocols"
      case ("bsp", "socket") => "_files"
      case ("test", "filter") => "_testsfqcn"
      case ("run", "main") => "_mainsfqcn"
      case _ => ""
    }
}
