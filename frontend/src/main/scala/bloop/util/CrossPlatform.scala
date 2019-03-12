package bloop.util

import java.util.Locale

object CrossPlatform {
  val OS = System.getProperty("os.name").toLowerCase(Locale.ENGLISH)
  val isWindows: Boolean = OS.contains("windows")
  val isMac: Boolean = OS.contains("mac")
}
