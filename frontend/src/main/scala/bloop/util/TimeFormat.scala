package bloop.util

object TimeFormat {

  /**
   * Outputs a reasonably pretty duration format out of some ms.
   *
   * It only supports until hours. If a duration includes days, it won't happen.
   * This shall not be an issue for our purposes because test executions don't
   * take hours to run.
   *
   * The duration can be negative because `sbt.testing` will use `-1` when no test is run.
   */
  def printUntilHours(durationMs: Long): String = {
    if (durationMs < 0) "0ms"
    else {
      import java.lang.Long
      val ms: Long = durationMs % 1000
      val s: Long = (durationMs / 1000) % 60
      if (s == 0) s"${ms}ms"
      else {
        val m: Long = (s % 3600) / 60
        if (m == 0) String.format("%d.%02ds", s, ms)
        else {
          val h: Long = s / 3600
          if (h == 0) String.format("%d:%02d.%02dm", m, s, ms)
          String.format("%d:%02d:%02d.%02d h", h, m, s, ms)
        }
      }
    }
  }
}
