package bloop.util

import bloop.cli.CliParsers
import bloop.cli.completion.Case

object FishCompleteGenerator {

  private val completeWithFlag: String =
    "complete -c bloop -f -n \"__assert_args_at_least_count %s; and __fish_seen_subcommand_from %s\" -l %s %s %s"
  private val desc = "-d \"%s\""
  private val values = "-xa '%s'"
  private val flagValue = "true false"

  private def completionFunction(cmdName: String, argName: String, isFlag: Boolean): Option[String] =
    (cmdName, argName, isFlag) match {
      case (_, "project", _) => Some("(_projects)")
      case (_, "configDir", _) => None
      case (_, "reporter", _) => Some("(_reporters)")
      case ("bsp", "protocol", _) => Some("(_protocols)")
      case ("bsp", "socket", _) => None
      case ("test", "filter", _) => Some("(_testsfqcn)")
      case ("run", "main", _) => Some("(_mainsfqcn)")
      case (_, _, true) => Some(flagValue)
      case _ => None
    }

  def main(args: Array[String]): Unit = {
    val completeCommands = CliParsers.CommandsMessages.messages.flatMap {
      case (commandName, messages) =>
        val tokensBeforeFlags = messages.args.find(_.name == "project").map(_ => "2").getOrElse("1")

        Seq(s"# complete for $commandName") ++
          messages.args
            .filterNot(arg => arg.noHelp || arg.name == "debug")
            .map(Case.kebabizeArg)
            .map { arg =>
              val value =
                completionFunction(commandName, arg.name, arg.isFlag).map(values.format(_))
              val description = arg.helpMessage.map(help => desc.format(help.message))
              completeWithFlag.format(
                tokensBeforeFlags,
                commandName,
                arg.name,
                description.getOrElse(""),
                value.getOrElse(""))
            } ++
          Seq("")
    }

    println(completeCommands.mkString("\n"))
  }
}
