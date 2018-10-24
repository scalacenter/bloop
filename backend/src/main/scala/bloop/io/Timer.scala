package bloop.io

import bloop.logging.{ LogContext, Logger }

object Timer {
  @inline def timed[T](logger: Logger, prefix: Option[String] = None)(op: => T): T = {
    val start = System.nanoTime()
    try op
    finally {
      val elapsed = (System.nanoTime() - start).toDouble / 1e6
      logger.debug(s"Elapsed ${prefix.map(s => s"($s)").getOrElse("")}: $elapsed ms")(LogContext.All)
    }
  }
}
