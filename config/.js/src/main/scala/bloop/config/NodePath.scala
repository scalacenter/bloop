package bloop.config

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("path", JSImport.Namespace)
object NodePath extends js.Object {
  def join(paths: String*): String = js.native
  def resolve(paths: String*): String = js.native
}
