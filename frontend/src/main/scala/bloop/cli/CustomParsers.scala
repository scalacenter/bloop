package bloop.cli

import java.nio.file.{Path, Paths}
import caseapp.core.ArgParser
import scala.util.Try

trait CustomParsers {
  implicit val fileParser: ArgParser[Path] = ArgParser.instance("A filepath parser") {
    case supposedPath: String =>
      val toPath = Try(Paths.get(supposedPath)).toEither
      toPath.left.map(t => s"The provided path ${supposedPath} is not valid: '${t.getMessage()}'.")
  }
}
