package bloop.cli.completion

import caseapp.core.{Arg, CommandMessages}

import bloop.Project
import bloop.cli.{BspProtocol, ReporterKind}

/**
 * A format for generating output that can be understood by
 * autocompletion engines.
 */
trait Format {

  /**
   * Return the completion item for `project`
   *
   * @param project The candidate for autocompletion
   * @return None, if the project shouldn't be autocompleted, otherwise Some(str) where
   *         `str` tells the autocompletion engine how to complete `project`.
   */
  def showProject(project: Project): Option[String]

  /**
   * Return the completion item for the command `name`.
   *
   * @param name     The name of the command
   * @param messages The `CommandMessages` representing the arguments of the command.
   * @return None, if the command shouldn't be autocompleted, otherwise Some(str) where
   *         `str` tells the autocompletion engine how to complete the command.
   */
  def showCommand(name: String, messages: CommandMessages): Option[String]

  /**
   * Return the completion item for the argument `arg` in command `commandName`.
   *
   * @param commandName The name of the command that defines this argument
   * @param arg         The argument to autocomplete
   * @return None, if the argument shouldn't be autocompleted, otherwise Some(str) where
   *         `str` tells the autocompletion engine how to complete the argument.
   */
  def showArg(commandName: String, arg: Arg): Option[String]

  /**
   * Return the completion item for the test `fqcn`.
   *
   * @param fqcn The fully qualified class name of the test to add to autocompletion.
   * @return None, if the test name shouldn't be autocompleted, otherwise Some(str) where
   *         `str` tells the autocompletion engine how to complete the test name.
   */
  def showTestName(fqcn: String): Option[String]

  /**
   * Return the completion item for the main class `fqcn`.
   *
   * @param fqcn The fully qualified class name of the main class
   * @return None, if the class name shouldn't be autocompleted, otherwise Some(str) where
   *         `str` tells the autocompletion engine how to complete the class name.
   */
  def showMainName(fqcn: String): Option[String]

  /**
   * Return the completion item for the reporter `reporter`.
   *
   * @param reporter The reporter to add to auto completions
   * @return None, if the reporter shouldn't be autocompleted, otherwise Some(str) where
   *         `str` tells the autocompletion engine how to complete the reporter.
   */
  def showReporter(reporter: ReporterKind): Option[String]

  /**
   * Return the completion item for the protocol `protocol`.
   *
   * @param protocol The protocol to add to auto completions
   * @return None, if the protocol shouldn't be autocompleted, otherwise Some(str) where
   *         `str` tells the autocompletion engine how to complete the protocol.
   */
  def showProtocol(protocol: BspProtocol): Option[String]
}
