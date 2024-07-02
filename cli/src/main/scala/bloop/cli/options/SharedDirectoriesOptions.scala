package bloop.cli.options

import caseapp._
import bloop.cli.Directories

// format: off
final case class SharedDirectoriesOptions(
  @Name("home")
    homeDirectory: Option[String] = None
) {
  // format: on

  def directories: Directories =
    homeDirectory.filter(_.trim.nonEmpty) match {
      case None =>
        Directories.default()
      case Some(homeDir) =>
        val homeDir0 = os.Path(homeDir, os.pwd)
        Directories.under(homeDir0)
    }
}

object SharedDirectoriesOptions {
  lazy val parser: Parser[SharedDirectoriesOptions] = Parser.derive
  implicit lazy val parserAux: Parser.Aux[SharedDirectoriesOptions, parser.D] = parser
  implicit lazy val help: Help[SharedDirectoriesOptions] = Help.derive
}
