package blossom.util

import java.util.concurrent.atomic.AtomicInteger

class Progress(total: Int,
               start: String = "[",
               end: String = "]",
               sym: String = "#") {
  private[this] val symLength = sym.length
  private[this] val counter   = new AtomicInteger(0)

  show()

  def increment(): Unit =
    counter.incrementAndGet()

  def show(): Unit = {
    val percent = ((counter.get.toDouble / total.toDouble) * 100.0).toInt
    print(
      start + sym * percent + " " * symLength * (100 - percent) + end + s" ($percent%)\r")
    if (counter.get == total) println()
  }

  def update(): Unit = {
    increment()
    show()
  }
}
