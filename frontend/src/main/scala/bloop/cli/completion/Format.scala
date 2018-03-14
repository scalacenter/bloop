package bloop.cli.completion

import caseapp.core.{Arg, CommandMessages}

import bloop.Project

trait Format {
  def showProject(project: Project): Option[String]
  def showCommand(name: String, messages: CommandMessages): Option[String]
  def showArg(commandName: String, arg: Arg): Option[String]
  def showTestName(fqcn: String): Option[String]
  def showMainName(fqcn: String): Option[String]
}
