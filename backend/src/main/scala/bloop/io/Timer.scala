package bloop.io

import bloop.logging.{DebugFilter, Logger}

object Timer {
  def timed[T](logger: Logger, prefix: Option[String] = None)(op: => T): T = {
    timed(logger.debug(_: String)(DebugFilter.All), prefix)(op)
  }

  def timed[T](log: String => Unit, prefix: Option[String])(op: => T): T = {
    val start = System.nanoTime()
    try op
    finally {
      val elapsed = (System.nanoTime() - start).toDouble / 1e6
      log(s"Elapsed ${prefix.map(s => s"($s)").getOrElse("")}: $elapsed ms")
    }
  }
}
