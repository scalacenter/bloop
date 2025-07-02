import scala.io.Source

object Main {
  def main(args: Array[String]): Unit = {
    val text = Source.fromResource("test.txt").getLines().mkString
    println(text)
    val text2 = Source.fromResource("test2.txt").getLines().mkString
    println(text2)
  }
}
