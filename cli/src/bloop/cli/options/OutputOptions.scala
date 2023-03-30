package bloop.cli.options

import caseapp._
import bloop.rifle.BloopRifleConfig

// format: off
final case class OutputOptions(
  @Recurse
    logging: LoggingOptions = LoggingOptions(),
  @Recurse
    compilationServer: SharedCompilationServerOptions = SharedCompilationServerOptions(),
  @Recurse
    directories: SharedDirectoriesOptions = SharedDirectoriesOptions()
) {
  // format: on
  def bloopRifleConfig: BloopRifleConfig =
    compilationServer.bloopRifleConfig(
      logging.logger,
      CoursierOptions().coursierCache(
        logging.logger.coursierLogger("Downloading Bloop")
      ),             // unused here
      "unused-java", // unused here
      directories.directories
    )
}

object OutputOptions {
  implicit lazy val parser: Parser[OutputOptions] = Parser.derive
  implicit lazy val help: Help[OutputOptions]     = Help.derive
}
