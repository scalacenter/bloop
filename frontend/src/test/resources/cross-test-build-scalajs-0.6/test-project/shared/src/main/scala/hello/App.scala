package hello

object App {
  def main(args: Array[String]): Unit = {
    // Do not touch the following line as its position is tested in bloop
    val a = 1 // This is unused and will cause a warning
    println(Hello.greet("world"))
  }
}
