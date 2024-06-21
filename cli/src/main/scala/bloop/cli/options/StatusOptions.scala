package bloop.cli.options

import caseapp._
import bloop.rifle.BloopRifleConfig
import coursier.cache.FileCache

// format: off
final case class StatusOptions(
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
      FileCache(), // shouldn't be used…
      "java", // shouldn't be used…
      directories.directories
    )

}

object StatusOptions {
  implicit lazy val parser: Parser[StatusOptions] = Parser.derive
  implicit lazy val help: Help[StatusOptions] = Help.derive
}
