package bloop.util

object Console {
  private val escape = "\u001b["
  private val cursorHome = "H"
  private val eraseScreen = "2J"
  private val eraseScrollbar = "3J"

  val clearCommand: String = escape + cursorHome + escape + eraseScreen + escape + eraseScrollbar
}
