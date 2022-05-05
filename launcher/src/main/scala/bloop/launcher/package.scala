package bloop

import java.io.PrintStream

package object launcher {
  // Override these here to ensure no accidental `println` (to be compliant with bsp spec)
  def print(msg: String, out: PrintStream): Unit = out.print(msg)
  def println(msg: String, out: PrintStream): Unit = out.println(msg)
  def printError(msg: String, out: PrintStream): Unit = println(s"error: ${msg}", out)
  def printQuoted(msg: String, out: PrintStream): Unit = {
    println(
      msg
        .split("\r\n|\n")
        .map(l => s"> $l")
        .mkString(lineSeparator),
      out
    )
  }

  /**
   * SHELL path implies preference for '\n' instead of Windows default.
   * @return '\n' if SHELL path recognized, system line.separator otherwise.
   */
  lazy val lineSeparator: String = Option(System.getenv("SHELL")) match {
    case Some(sh) if sh.toLowerCase.matches("/.*/bin/[a-z]*sh(.exe)") => "\n"
    case _ => System.getProperty("line.separator", "\n")
  }
}
