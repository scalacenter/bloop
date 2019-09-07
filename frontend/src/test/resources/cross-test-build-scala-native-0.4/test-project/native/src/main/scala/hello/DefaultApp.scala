package hello

import scalanative.unsafe._
import scalanative.libc.stdio

object DefaultApp {
  def main(args: Array[String]): Unit = Zone { implicit z =>
    stdio.vprintf(c"Hello, world from DefaultApp!\n", toCVarArgList())
  }
}
