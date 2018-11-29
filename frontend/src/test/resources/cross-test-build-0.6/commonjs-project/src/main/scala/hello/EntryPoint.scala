package hello

import scala.scalajs.js
import js.annotation._

@ScalaJSDefined
@JSExportTopLevel("EntryPoint")
class EntryPoint extends js.Object {
  def test(): Unit = {
    println("Hello World")
  }
}
