package bloop.io

object Filenames {
  lazy val specialCharacters = "[^\\p{Alnum}-]".r
  def escapeSpecialCharacters(filename: String): String =
    specialCharacters.replaceAllIn(filename, "_")
}
