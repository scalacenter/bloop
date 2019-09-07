package hello

import scalanative.native._

object DefaultApp {
  def main(args: Array[String]): Unit = {
    stdio.printf(c"Hello, world from DefaultApp!\n")
  }
}
