package bloop.io

object Timer {

  @inline def timed[T](op: => T): T = {
    val start   = System.nanoTime()
    val result  = op
    val elapsed = (System.nanoTime() - start).toDouble / 1e6
    println(s"Elapsed: $elapsed ms")
    result
  }

}
