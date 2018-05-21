package bloop.util

import java.util.concurrent.TimeUnit

/** Adapted from https://gist.github.com/krishnanraman/0a17ef012c1cb28edf3e44214f5d4e83 */
object TimeFormat {
  final val Units = List(
    (TimeUnit.DAYS,    "day"),
    (TimeUnit.HOURS,   "hour"),
    (TimeUnit.MINUTES, "minute"),
    (TimeUnit.SECONDS, "second"),
    (TimeUnit.MILLISECONDS, "millisecond"))

  def humanReadable(milliseconds: Long): String =
    Units.foldLeft(("", milliseconds)) { case ((accFmt, accRest), (unit, name)) =>
      val value = unit.convert(accRest, TimeUnit.MILLISECONDS)
      val fmt =
        if (value == 0) accFmt
        else {
          val pluralS   = if (value == 1) "" else "s"
          val formatted = s"$value $name$pluralS"
          if (accFmt.isEmpty) formatted else s"$accFmt, $formatted"
        }

      (fmt, accRest - TimeUnit.MILLISECONDS.convert(value, unit))
    }._1
}
