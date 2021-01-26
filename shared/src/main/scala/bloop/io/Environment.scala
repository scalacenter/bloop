package bloop.io

object Environment {

  /**
   * Returns "\n" if both OSTYPE and SHELL are nonEmpty, returns `System.lineSeparator` otherwise.
   */
  def lineSeparator: String = if (validOstype.nonEmpty) shellLineSeparator else System.lineSeparator

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
   * Unlike [[lineSeparator]], only depends on the value of SHELL.
   *
   * @return preferred lineSeparator for SHELL environments, or `System.lineSeparator` otherwise.
   */
  private def shellLineSeparator: String = if (validShell.nonEmpty) "\n" else sysLineSeparator

  /**
   * Preferred line ending for SHELL environments.
   *
   * An alias for `shellLineSeparator`.
   *
   * @example
   * {{{
   *   print(someText + EOL)
   * }}}
   */
  lazy val EOL: String = shellLineSeparator

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
  lazy val shell: String = getenvOpt("SHELL").getOrElse("")

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
  def sysLineSeparator: String = propsOpt("line.separator").getOrElse("\n")

  def propsOpt(propname: String): Option[String] = Option(sys.props(propname))

  def getenvOpt(varname: String): Option[String] = Option(System.getenv(varname))

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

  /*
   * Returns the empty String unless OSTYPE is defined.
   */
  private lazy val ostype: String = getenvOpt("OSTYPE").getOrElse("")

  /*
   * Returns validated, lowercase abbreviated OSTYPE value, or empty string.
   *
   * Replaced by the entry of [[validOsTypes]] having [[ostype]] as a substring.
   *
   */
  private def validOstype: String = {
    ostype.toLowerCase(java.util.Locale.ENGLISH) match {
      case "" => ""
      case os =>
        validOstypes.find { os.contains(_) }.getOrElse("")
    }
  }

  /*
   * A list of valid ostype substrings.  The lower-case OSTYPE environment
   * variable must contain one of these substrings to be supported.
   */
  private lazy val validOstypes = Seq(
    "cygwin",
    "msys",
    "mingw",
    "linux",
    "darwin",
    "osx",
    "sunos",
    "solaris",
    "aix"
  )

}
