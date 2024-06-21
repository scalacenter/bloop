package bloop.cli.options

import caseapp._


// format: off
@HelpMessage("Stop Bloop if an instance is running.")
final case class ExitOptions(
  @Recurse
    logging: LoggingOptions = LoggingOptions(),
  @Recurse
    compilationServer: SharedCompilationServerOptions = SharedCompilationServerOptions(),
  @Recurse
    directories: SharedDirectoriesOptions = SharedDirectoriesOptions(),
  @Recurse
    coursier: CoursierOptions = CoursierOptions()
)
// format: on

object ExitOptions {
  implicit lazy val parser: Parser[ExitOptions] = Parser.derive
  implicit lazy val help: Help[ExitOptions] = Help.derive
}
