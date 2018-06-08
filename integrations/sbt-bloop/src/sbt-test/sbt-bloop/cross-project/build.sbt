// shadow sbt-scalajs' crossProject and CrossType until Scala.js 1.0.0 is released
import sbtcrossproject.{crossProject, CrossType}

val sharedSettings = Seq(
  scalaVersion := "2.11.12",
  InputKey[Unit]("check") := {
    val expected = complete.DefaultParsers.spaceDelimited("").parsed.head
    val config = bloopConfigDir.value / s"${thisProject.value.id}.json"
    val lines = IO.read(config).replaceAll("\\s", "")
    assert(lines.contains(s""""platform":"$expected""""))
  }
)

lazy val bar =
  crossProject(JSPlatform, JVMPlatform, NativePlatform)
    .settings(sharedSettings)

lazy val barJS = bar.js
lazy val barJVM = bar.jvm
lazy val barNative = bar.native
