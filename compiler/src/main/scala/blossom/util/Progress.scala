package blossom.util

class Progress(total: Int, start: String = "[", end: String = "]", sym: String = "#") {
  private[this] val symLength  = sym.length
  private[this] var value: Int = 0

  show()

  def increment(): Unit =
    value += 1

  def show(): Unit = {
    val percent = ((value.toDouble / total.toDouble) * 100.0).toInt
    print(start + sym * percent + " " * symLength * (100 - percent) + end + s" ($percent%)\r")
    if (value == total) println()
  }

  def update(): Unit = {
    increment()
    show()
  }
}
