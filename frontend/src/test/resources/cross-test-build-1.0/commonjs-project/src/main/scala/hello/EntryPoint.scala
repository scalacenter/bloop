package hello

import scala.scalajs.js
import js.annotation._

@JSExportTopLevel("EntryPoint")
object EntryPoint {
  def test(): Unit = {
    println("Hello World")
  }
}
