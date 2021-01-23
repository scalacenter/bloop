package bloop.io

object Environment {

  def isWindows: Boolean = scala.util.Properties.isWin

  // value of system environment variable OSTYPE
  def ostype: String = getenvOpt("OSTYPE").getOrElse("")

  /*
   * environment variable SHELL defined by default in CYGWIN, MinGW, msys, Git Bash.
   */
  def shell: String = getenvOpt("SHELL").getOrElse("")

  /*
   * Validated shell name, or empty string.
   */
  def validShell: String = validShells.find { _ == shell } match {
    case Some(sh) => sh
    case _ => ""
  }

  /*
   * The preferred lineSeparator for SHELL environments.
   */
  def lineSeparator: String = if (validShell.nonEmpty) "\n" else sysLineSeparator

  /*
   * NOTE: defining System property "line.separator" does not seem to
   * override the value of System.lineSeparator, so this is preferred to
   * permit programmability at jvm launch time.
   *
   * System properties may be redefined on JVM startup, e.g.:
   *     <pre>
   *     $ bloop -Dline.separator=$'\n' server
   *     </pre>
   *
   * This should not be used in Windows unless validShell == "".
   */
  private def sysLineSeparator: String = propsOpt("line.separator").getOrElse("\n")

  /*
   * Validated, lowercased and abbreviated OSTYPE value, or empty string.
   */
  lazy val validOstype: String = {
    ostype match {
      case "" =>
        ""
      case os =>
        val lcos = os.toLowerCase(java.util.Locale.ENGLISH)
        validOstypes.find { lcos.contains(_) } match {
          case Some(os) =>
            os // lowercase abbreviated value
          case _ =>
            ""
        }
    }
  }

  /*
   * EOL is:
   *    '\r\n' on Windows, unless SHELL is a unix-like shell environment
   *    '\n'   everywhere else
   */
  lazy val EOL: String = lineSeparator

  def propsOpt(propname: String): Option[String] = Option(sys.props(propname))

  def getenvOpt(varname: String): Option[String] = Option(System.getenv(varname))

  /*
   * The safe general way to split Strings to lines.   Makes minimal assumptions
   * about the source of the String.
   *
   * Does not depend on editor end-of-line configuration.
   * Correctly splits strings from any of the common OSTYPES.
   */
  def lineSplit(str: String): Array[String] = str.split(END_OF_LINE_MATCHER, -1)

  /*
   * Add extension method to String for reliable line splitting.
   */
  implicit class LineSplitter(str: String) {
    def splitLines: Array[String] = str.split(END_OF_LINE_MATCHER, -1)
  }

  /*
   * reliably split strings to lines, regardless of where they originated
   * borrowed from zinc DiagnosticsReporter.scala
   */
  def END_OF_LINE_MATCHER = "\r\n|\r|\n"

  /*
   * A list of valid ostype substrings.  The lower-case OSTYPE environment
   * variable must contain one of these substrings to be supported.
   */
  lazy val validOstypes = Seq(
    "cygwin",
    "msys",
    "mingw"
  )
  /*
   * A list of valid shell paths.  The SHELL environment variable must
   * match one of these paths to be considered a valid shell.
   */
  lazy val validShells = Seq(
    "/bin/sh",
    "/bin/ash",
    "/bin/bash",
    "/bin/dash",
    "/bin/mksh",
    "/bin/pdksh",
    "/bin/posh",
    "/bin/tcsh",
    "/bin/zsh",
    "/usr/bin/sh",
    "/usr/bin/ash",
    "/usr/bin/bash",
    "/usr/bin/dash",
    "/usr/bin/mksh",
    "/usr/bin/pdksh",
    "/usr/bin/posh",
    "/usr/bin/tcsh",
    "/usr/bin/zsh"
  )
}
