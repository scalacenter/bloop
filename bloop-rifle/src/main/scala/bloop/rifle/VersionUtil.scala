package bloop.rifle

import scala.util.Try

object VersionUtil {

  /**
   * @param jvmVersion,
   *   for example '1.8.0.22' or '11.0.2'
   * @return
   *   jvm release version (8, 11)
   */
  val jvmReleaseRegex = "(1[.])?(\\d+)"
  def jvmRelease(jvmVersion: String): Option[Int] = for {
    regexMatch <- jvmReleaseRegex.r.findFirstMatchIn(jvmVersion)
    versionString <- Option(regexMatch.group(2))
    versionInt <- Try(versionString.toInt).toOption
  } yield versionInt

  /**
   * @param input
   *   `java -version` output`
   * @return
   *   jvm release version (8, 11)
   */
  def parseJavaVersion(input: String): Option[Int] = for {
    firstMatch <- s""".*version .($jvmReleaseRegex).*""".r.findFirstMatchIn(input)
    versionNumberGroup <- Option(firstMatch.group(1))
    versionInt <- jvmRelease(versionNumberGroup)
  } yield versionInt

  def parseBloopAbout(stdoutFromBloopAbout: String): Option[BloopRifle.BloopServerRuntimeInfo] = {

    val bloopVersionRegex = "bloop v(.*)\\s".r
    val bloopJvmRegex = "Running on Java ... v([0-9._A-Za-z\\-]+) [(](.*)[)]".r
    // https://regex101.com/r/lT624X/1

    for {
      bloopVersion <- bloopVersionRegex.findFirstMatchIn(stdoutFromBloopAbout).map(_.group(1))
      bloopJvmVersion <- bloopJvmRegex.findFirstMatchIn(stdoutFromBloopAbout).map(_.group(1))
      javaHome <- bloopJvmRegex.findFirstMatchIn(stdoutFromBloopAbout).map(_.group(2))
      jvmRelease <- VersionUtil.jvmRelease(bloopJvmVersion)
    } yield BloopRifle.BloopServerRuntimeInfo(
      bloopVersion = BloopVersion(bloopVersion),
      jvmVersion = jvmRelease,
      javaHome = javaHome
    )
  }
}
