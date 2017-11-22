package bloop.util

import java.util.concurrent.atomic.AtomicInteger

import bloop.logging.Logger

class Progress(logger: Logger,
               private var total: Int = 100,
               start: String = "[",
               end: String = "]",
               sym: String = "#") {
  private[this] val symLength = sym.length
  private[this] val counter = new AtomicInteger(0)

  show()

  def increment(): Unit = {
    counter.incrementAndGet()
    ()
  }

  def show(): Unit = {
    val percent = ((counter.get.toDouble / total.toDouble) * 100.0).toInt min 100
    logger.progress(
      start + sym * percent + " " * symLength * (100 - percent) + end + s" ($percent%)\r")
    if (counter.get >= total) logger.progress("\n")
  }

  def setTotal(total: Int): Unit = {
    this.total = total
    show()
  }

  def update(): Unit = {
    increment()
    show()
  }
}
