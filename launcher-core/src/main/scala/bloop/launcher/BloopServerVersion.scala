package bloop.launcher

import java.io.PrintStream

case class BloopServerVersion(
    major: Int,
    minor: Int,
    patch: Int
)

object BloopServerVersion {
  def apply(serverVersion: String, out: PrintStream): Option[BloopServerVersion] = {
    serverVersion.split('.') match {
      case Array(majStr, minStr, rest @ _*)
          if majStr.nonEmpty && majStr.forall(_.isDigit) && minStr.nonEmpty && minStr.forall(
            _.isDigit
          ) =>
        val patchNumber = rest match {
          case Seq(patchStr, _*) if patchStr.nonEmpty && patchStr.forall(_.isDigit) =>
            patchStr.toInt
          case _ => 0
        }
        Some(BloopServerVersion(majStr.toInt, minStr.toInt, patchNumber))
      case unexpectedItems =>
        printError(
          s"Expected major and minor version numbers in ${serverVersion}, obtained ${unexpectedItems.toSeq}",
          out
        )
        None
    }
  }

  /**
   * Checks that a bloop version can be used with the launcher.
   * Compatible bloop versions are those that are the same or bigger than 1.1.2.
   *
   * @param version The bloop version we want to install if it's missing.
   * @return Whether the version in compatible or not depending on if it can be parsed or not.
   */
  def isValidBloopVersion(version: BloopServerVersion): Boolean = {
    (version.major == 1 && version.minor == 1 && version.patch == 2) ||
    (version.major >= 1 && version.minor >= 2) ||
    (version.major >= 1 && version.minor >= 2)
  }
}
