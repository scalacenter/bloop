package bloop.io

import bloop.logging.Logger

object Timer {

  @inline def timed[T](logger: Logger)(op: => T): T = {
    val start = System.nanoTime()
    val result = op
    val elapsed = (System.nanoTime() - start).toDouble / 1e6
    logger.info(s"Elapsed: $elapsed ms")
    result
  }

}
