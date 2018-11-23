package bloop.util
import java.util.Locale

object OS {
  def isWindows: Boolean =
    System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")
  def isMac: Boolean =
    System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("mac")
}
