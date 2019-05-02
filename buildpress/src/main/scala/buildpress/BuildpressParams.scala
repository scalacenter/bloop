package buildpress

import buildpress.io.AbsolutePath

import java.nio.file.Path
import java.nio.file.Paths

import scala.util.Try

import caseapp.core.{ArgParser, Parser}
import caseapp.{ExtraName, HelpMessage}

final case class BuildpressParams(
    @ExtraName("file")
    @HelpMessage("The file containing a list of '(id, uri)' that buildpress will export")
    buildpressFile: AbsolutePath,
    @HelpMessage("The bloop version to export a build with")
    bloopVersion: String,
    @HelpMessage(
      "The base directory pointing to the bloop base dir, required if bloop version is not stable"
    )
    bloopBaseDirectory: Option[AbsolutePath]
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
