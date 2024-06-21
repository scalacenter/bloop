package bloop.cli.options

import caseapp._
import bloop.rifle.BloopRifleConfig

// format: off
@HelpMessage("Starts a Bloop instance, if none is running")
final case class StartOptions(
  @Recurse
    logging: LoggingOptions = LoggingOptions(),
  @Recurse
    compilationServer: SharedCompilationServerOptions = SharedCompilationServerOptions(),
  @Recurse
    directories: SharedDirectoriesOptions = SharedDirectoriesOptions(),
  @Recurse
    jvm: SharedJvmOptions = SharedJvmOptions(),
  @Recurse
    coursier: CoursierOptions = CoursierOptions(),
  @Name("f")
    force: Boolean = false
) {
  // format: on

  def bloopRifleConfig: BloopRifleConfig =
    DefaultOptions.bloopRifleConfig(jvm, compilationServer, logging, coursier, directories)
}

object StartOptions {

  implicit lazy val parser: Parser[StartOptions] = Parser.derive
  implicit lazy val help: Help[StartOptions] = Help.derive
}
