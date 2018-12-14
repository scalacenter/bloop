package bloop

import java.io.PrintStream

package object launcher {
  def print(msg: String, out: PrintStream): Unit = out.print(msg)
  def println(msg: String, out: PrintStream): Unit = out.println(msg)
  def printError(msg: String, out: PrintStream): Unit = println(s"error: ${msg}", out)
  def printQuoted(msg: String, out: PrintStream): Unit = {
    println(
      msg
        .split(System.lineSeparator())
        .map(l => s"> $l")
        .mkString(System.lineSeparator()),
      out
    )
  }
}
