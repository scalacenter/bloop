package bloop.util

import java.{util => ju}
import ju.concurrent.TimeUnit

object TimeFormat {
  def readableMillis(nanos: Long): String = {
    import java.text.DecimalFormat
    import java.text.DecimalFormatSymbols
    val seconds = TimeUnit.MILLISECONDS.toSeconds(nanos)
    if (seconds > 9) readableSeconds(seconds)
    else {
      val ms = TimeUnit.MILLISECONDS.toMillis(nanos)
      if (ms < 100) {
        s"${ms}ms"
      } else {
        val partialSeconds = ms.toDouble / 1000
        new DecimalFormat("#.##s", new DecimalFormatSymbols(ju.Locale.US))
          .format(partialSeconds)
      }
    }
  }

  def readableSeconds(n: Long): String = {
    val minutes = n / 60
    val seconds = n % 60
    if (minutes > 0) {
      if (seconds == 0) s"${minutes}m"
      else s"${minutes}m${seconds}s"
    } else {
      s"${seconds}s"
    }
  }
}
