package bloop.io

object OsEnv {
  // EOL is:
  //    '\r\n' on Windows, except if SHELL == a recognized unix-like shell environment
  //    '\n'   everywhere else
  lazy val EOL:String = eol
 
  // SHELL is defined in CYGWIN, MinGW, msys, Git Bash, etc.
  def eol:String = getenvOpt("SHELL") match {
    case Some(str) if str.matches(".*/(bash|csh|zsh|sh)$") =>
      "\n"
    case _ =>
      sysLineSeparator
  }

  // System properties can be redefined on JVM startup:
  //
  //     <code>java -Dline.separator=$'\n' [blah-blah-blah]</code>
  //
  def sysLineSeparator = propsOpt("line.separator").getOrElse("\n")

  def propsOpt(propname:String) = Option(sys.props(propname))

  def getenvOpt(varname:String) = Option(System.getenv(varname))

  def lineSplit(str:String):Array[String] = str.split(END_OF_LINE_MATCHER,-1)  

  // reliably split strings to lines, regardless of where they originated
  def END_OF_LINE_MATCHER = "(\r\n)|[\r]|[\n]" // from zinc DiagnosticsReporter.scala
}
