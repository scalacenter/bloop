unmanagedSourceDirectories in Compile ++= {
  val sbtBloopRoot = baseDirectory.value.getParentFile / "sbt-bloop" / "src" / "main"
  val sbtBinVersion = sbtBinaryVersion.value
  Seq(
    sbtBloopRoot / s"scala-sbt-$sbtBinVersion",
    sbtBloopRoot / "scala"
  )
}
