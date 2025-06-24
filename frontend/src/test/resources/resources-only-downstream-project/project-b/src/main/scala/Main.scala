import scala.io.Source

@main def main() =
  val text = Source.fromResource("test.txt").getLines().mkString
  println(text)
