libraryDependencies += ("ch.epfl.scala" % "jarjar" % "1.7.2-patched")
  .exclude("org.apache.maven", "maven-plugin-api")
  .exclude("org.apache.ant", "ant")

unmanagedSourceDirectories in Compile ++= {
  val baseDir = baseDirectory.value.getParentFile.getParentFile.getParentFile
  List(
    baseDir / "sbt-shading" / "src" / "main" / "scala",
    baseDir / "sbt-shading" / "src" / "main" / "java"
  )
}
