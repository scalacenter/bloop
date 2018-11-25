package hello

object ShowCwd {
  def main(args: Array[String]): Unit = {
    println(System.getProperty("user.dir"))
  }
}
