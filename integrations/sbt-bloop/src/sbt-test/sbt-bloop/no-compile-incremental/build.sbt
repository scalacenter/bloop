lazy val commonSettings = Seq(
  scalaVersion := "2.11.12",
  scalacOptions ++= Seq(
    "-deprecation",
    "-unchecked",
    "-encoding", "UTF-8",
    "-Yclosure-elim",
    "-Yinline",
    "-Xverify",
    "-feature"
  )
)

lazy val jmh = project
  .settings(commonSettings: _*)