import scala.io.Source

object Main {
  def main(args: Array[String]): Unit = {
    val text = Source.fromResource("test.txt").getLines().mkString
    println(text)
  }
}
