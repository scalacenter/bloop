package bloop.io

object Environment {

  /**
   * Returns preferred SHELL environment lineSeparator:
   *    '\r\n' on Windows, unless SHELL environment is recognized.
   *    '\n'   everywhere else
   *
   * The default ending in Windows is '\r\n', but either of two conditions can
   * change the value of [[shellLineSeparator]]:
   *    1. if [[validShell]] is nonEmpty
   *    2. if system property `"line.separator"` is modified
   *
   * @return preferred SHELL lineSeparator, or system property "line.separator"` otherwise.
   *
   * @example
   * {{{
   *   print(someText + lineSeparator)
   * }}}
   */
  def lineSeparator: String = if (validShell.nonEmpty) "\n" else sysLineSeparator

  /**
   * Return the canonical lowercased shell name, or empty string.
   *
   * `shell` is valid if when lowercased, it has a `validShells` entry as substring.
   *
   * @return the matching `validShells` entry, if there is a match; empty string otherwise.
   *
   * @example if {{{SHELL=/usr/bin/bash}}}, returns {{{/bin/bash}}}.
   *
   */
  private def validShell: String = validShells.find { _.endsWith(shellLC) } match {
    case Some(sh) => sh
    case _ => ""
  }

  /*
   * Return lowercase shell with possible .exe extension removed.
   */
  private[this] def shellLC = shell.toLowerCase(java.util.Locale.ENGLISH) match {
    case sh if sh.endsWith(".exe") => sh.replaceFirst(".exe$", "")
    case sh => sh
  }

  /*
   * Return the name of the SHELL environment variable, or empty string.
   *
   * Normally set in CYGWIN, MinGW, msys, Git Bash shell environments.
   */
  lazy val shell: String = Option(System.getenv("SHELL")).getOrElse("")

  /*
   * Returns current value of system property `"line.separator"`.
   *
   * System.lineSeparator may only be redefined at JVM startup,
   * whereas this may be redefined on the fly in client code.
   *
   * System.lineSeparator always returns the initial value of system property "line.separator".
   * @see [[https://docs.oracle.com/javase/8/docs/api/java/lang/System.html#lineSeparator--]]
   *
   * System properties may be redefined on JVM startup, e.g., from a shell environment:
   * @example
   *  {{{
   *  $ java -J-Dline.separator=$'\n' ...
   *  }}}
   *
   */
  private def sysLineSeparator: String = System.getProperty("line.separator", "\n")

  /**
   * Extend String for reliable line splitting.
   *
   * @example
   * {{{
   *   val lines = text.splitLines // equivalent to `text.split(END_OF_LINE_MATCHER)`
   * }}}
   *
   */
  implicit class LineSplitter(str: String) {
    /*
     * The safe general way to split Strings to lines.   Makes minimal assumptions
     * about the source of the String.
     *
     * Does not depend on editor end-of-line configuration.
     * Correctly splits strings from any of the common OSTYPES.
     */
    def splitLines: Array[String] = str.split(END_OF_LINE_MATCHER, -1)
  }

  /**
   * Regular expression that reliably splits Strings into lines.
   *
   * Effective for all commonly encountered line endings:
   *  - "\n"
   *  - "\r\n"
   *  - "\r"
   */
  private def END_OF_LINE_MATCHER = "\n|\r\n|\r"

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
    "/bin/zsh"
  )
}
