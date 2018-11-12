package hello

object App {
  def main(args: Array[String]): Unit = {
    val a = 1 // This is unused and will cause a warning
    println(Hello.greet("world"))
  }
}
