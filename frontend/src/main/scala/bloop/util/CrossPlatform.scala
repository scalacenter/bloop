package bloop.util

import java.util.Locale

object CrossPlatform {
  val OS: String = System.getProperty("os.name").toLowerCase(Locale.ENGLISH)
  val isWindows: Boolean = OS.contains("windows")
  val isMac: Boolean = OS.contains("mac")
  val isM1: Boolean = sys.props.getOrElse("os.arch", "").toLowerCase(Locale.ROOT) == "aarch64"
}
