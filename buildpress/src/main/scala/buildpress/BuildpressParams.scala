package buildpress

import java.nio.file.{Path, Paths}
import scala.util.Try
import bloop.io.AbsolutePath
import caseapp.core.{ArgParser, Parser}
import caseapp.{ExtraName, HelpMessage}

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
  implicit val pathParser: ArgParser[Path] = ArgParser.instance("path") {
    case supposedPath: String =>
      val toPath = Try(Paths.get(supposedPath)).toEither
      toPath.left.map(t => s"The provided path ${supposedPath} is not valid: '${t.getMessage()}'.")
  }

  implicit val absolutePathParser: ArgParser[AbsolutePath] = ArgParser.instance("absolute path") {
    case supposedPath: String =>
      val toPath = Try(AbsolutePath(supposedPath)).toEither
      toPath.left.map(t => s"The provided path ${supposedPath} is not valid: '${t.getMessage()}'.")
  }

  implicit val buildpressParamsParser: Parser[BuildpressParams] = Parser.generic
}
