package buildpress

import java.nio.file.Path
import java.nio.file.Paths

import scala.util.Try

import bloop.io.AbsolutePath

import caseapp.ExtraName
import caseapp.Help
import caseapp.HelpMessage
import caseapp.Parser
import caseapp.core.Error
import caseapp.core.argparser.ArgParser
import caseapp.core.argparser.SimpleArgParser

final case class BuildpressParams(
    @ExtraName("i")
    @HelpMessage(
      """One of:
        |- the path of the file containing a list of '(id, uri)' that buildpress will export
        |- the path of the directory containing the project that buildpress will export""".stripMargin
    )
    input: AbsolutePath,
    @HelpMessage("The bloop version to export a build with")
    bloopVersion: String,
    @HelpMessage("The buildpress home directory where repositories are cloned and cached")
    buildpressHome: AbsolutePath,
    @HelpMessage("Regenerate bloop configuration files for every repository")
    regenerate: Boolean = false,
    @HelpMessage("Clear buildpress cache")
    clearRepoCache: Boolean = false
)

object BuildpressParams {
  implicit val pathParser: ArgParser[Path] = SimpleArgParser.from("path") {
    case supposedPath: String =>
      val toPath = Try(Paths.get(supposedPath)).toEither
      toPath.left.map(t => Error.MalformedValue("path", s"$supposedPath (${t.getMessage()})"))
  }

  implicit val absolutePathParser: ArgParser[AbsolutePath] = SimpleArgParser.from("absolute path") {
    case supposedPath: String =>
      val toPath = Try(AbsolutePath(supposedPath)).toEither
      toPath.left.map(t =>
        Error.MalformedValue("absolute path", s"$supposedPath (${t.getMessage()})")
      )
  }

  implicit lazy val parser: Parser[BuildpressParams] = Parser.derive
  implicit lazy val help: Help[BuildpressParams] = Help.derive
}
