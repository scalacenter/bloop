package bloop.cli.util

import coursier.jvm.JvmIndex

import java.io.IOException
import java.nio.charset.Charset

import bloop.rifle.VersionUtil.parseJavaVersion
import scala.util.{Properties, Try}

object OsLibc {

  lazy val isMusl: Option[Boolean] = {

    def tryRun(cmd: String*): Option[os.CommandResult] =
      try {
        val res = os
          .proc(cmd)
          .call(
            mergeErrIntoOut = true,
            check = false
          )
        Some(res)
      } catch {
        case _: IOException =>
          None
      }

    val getconfResOpt = tryRun("getconf", "GNU_LIBC_VERSION")
    if (getconfResOpt.exists(_.exitCode == 0)) Some(false)
    else {

      val lddResOpt = tryRun("ldd", "--version")

      val foundMusl = lddResOpt.exists { lddRes =>
        (lddRes.exitCode == 0 || lddRes.exitCode == 1) &&
        lddRes.out.text(Charset.defaultCharset()).contains("musl")
      }

      if (foundMusl)
        Some(true)
      else {
        val libPath = os.Path("/lib")
        val inLib = if (libPath.toIO.exists()) os.list(libPath).map(_.last) else Nil
        if (inLib.exists(_.contains("-linux-gnu"))) Some(false)
        else if (inLib.exists(name => name.contains("libc.musl-") || name.contains("ld-musl-")))
          Some(true)
        else {
          val sbinPath = os.Path("/usr/sbin")
          val inUsrSbin =
            if (sbinPath.toIO.exists()) os.list(sbinPath).map(_.last) else Nil
          if (inUsrSbin.exists(_.contains("glibc"))) Some(false)
          else None
        }
      }
    }
  }

  // FIXME These values should be the default ones in coursier-jvm

  lazy val jvmIndexOs: String = {
    val default = JvmIndex.defaultOs()
    if (default == "linux" && isMusl.getOrElse(false)) "linux-musl"
    else default
  }

  def baseDefaultJvm(os: String, jvmVersion: String): String = {
    def java17OrHigher = Try(jvmVersion.takeWhile(_.isDigit).toInt).toOption
      .forall(_ >= 17)
    if (os == "linux-musl") s"liberica:$jvmVersion" // zulu could work too
    else if (java17OrHigher) s"temurin:$jvmVersion"
    else s"adopt:$jvmVersion"
  }

  def javaHomeVersion(javaHome: os.Path): (Int, String) = {
    val ext = if (Properties.isWin) ".exe" else ""
    val javaCmd = (javaHome / "bin" / s"java$ext").toString

    val javaVersionOutput = os
      .proc(javaCmd, "-version")
      .call(
        cwd = os.pwd,
        stdout = os.Pipe,
        stderr = os.Pipe,
        mergeErrIntoOut = true
      )
      .out
      .text()
      .trim()
    val javaVersion = parseJavaVersion(javaVersionOutput).getOrElse {
      throw new Exception(s"Could not parse java version from output: $javaVersionOutput")
    }
    (javaVersion, javaCmd)
  }

}
