package bloop.io

import scala.util.matching.Regex
object Filenames {
  lazy val specialCharacters: Regex = "[^\\p{Alnum}-]".r
  def escapeSpecialCharacters(filename: String): String =
    specialCharacters.replaceAllIn(filename, "_")
}
