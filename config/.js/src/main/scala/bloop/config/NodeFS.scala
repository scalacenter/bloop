package bloop.config

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("fs", JSImport.Namespace)
object NodeFS extends js.Object {
  def readFileSync(path: String, encoding: String): String = js.native
  def writeFileSync(path: String, data: String): Unit = js.native
  def mkdtempSync(prefix: String, encoding: String): String = js.native
  def openSync(path: String, flags: String): Int = js.native
  def closeSync(fd: Int): Unit = js.native
  def rmdirSync(path: String, options: RmDirOptions): Unit = js.native

  trait RmDirOptions extends js.Object {
    val recursive: Boolean
  }
}
