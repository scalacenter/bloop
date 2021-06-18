package bloop.io

object Environment {

  /**
   * Returns preferred SHELL environment lineSeparator:
   *    '\r\n' on Windows, unless SHELL environment is recognized.
   *    '\n'   everywhere else
   *
   * The default ending in Windows is '\r\n', but either of two conditions can
   * change the value of [[lineSeparator]]:
   *    1. if `SHELL` is recognized as valid
   *    2. if system property `"line.separator"` is modified
   *
   * @return preferred SHELL lineSeparator, or system property "line.separator"` otherwise.
   *
   * @example
   * {{{
   *   print(someText + lineSeparator)
   * }}}
   */
  val lineSeparator: String = Option(System.getenv("SHELL")) match {
    case Some(currentShell) if validShells.exists(sh => currentShell.contains(sh)) => "\n"
    case _ => System.getProperty("line.separator", "\n")
  }

  /**
   * Extend String for reliable line splitting.
   *
   * @example
   * {{{
   *   val lines = text.splitLines // equivalent to `text.split(END_OF_LINE_MATCHER)`
   * }}}
   */
  implicit class LineSplitter(str: String) {
    /*
     * The safe general way to split Strings to lines.   Makes minimal assumptions
     * about the source of the String.
     *
     * Does not depend on editor end-of-line configuration.
     * Correctly splits strings from any of the common OSTYPES.
     */
    def splitLines: Array[String] = str.split(END_OF_LINE_MATCHER)
  }

  /**
   * Regular expression that reliably splits Strings into lines.
   *
   * Effective for all commonly encountered line endings:
   *  - "\n"
   *  - "\r\n"
   *  - "\r"
   */
  def END_OF_LINE_MATCHER = "\r\n|\n"

  /*
   * A list of valid shell paths.  The SHELL environment variable must
   * have one of these entries as a substring to be considered a valid shell.
   */
  private lazy val validShells = Seq(
    "/bin/sh",
    "/bin/ash",
    "/bin/bash",
    "/bin/dash",
    "/bin/mksh",
    "/bin/pdksh",
    "/bin/posh",
    "/bin/tcsh",
    "/bin/zsh",
    "/bin/fish"
  )
}
