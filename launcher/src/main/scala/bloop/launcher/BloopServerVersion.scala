package bloop.launcher

import coursier.core.Version
import java.io.PrintStream

case class BloopServerVersion(
    major: Int,
    minor: Int,
    patch: Int
)

object BloopServerVersion {
  def apply(serverVersion: String, out: PrintStream): Option[BloopServerVersion] = {
    Version(serverVersion).items.toList match {
      case Version.Number(major) :: Version.Number(minor) :: rest =>
        val patchNumber = rest match {
          case Version.Number(patch) :: _ => patch
          case _ => 0
        }

        Some(BloopServerVersion(major, minor, patchNumber))
      case unexpectedItems =>
        printError(
          s"Expected major and minor version numbers in ${serverVersion}, obtained $unexpectedItems",
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
