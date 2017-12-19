val root = project
  .in(file("."))
  .settings(
    addSbtPlugin("com.lucidchart" % "sbt-scalafmt" % "1.14"),
    addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0"),
    addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.2.27"),
    addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6"),
    addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3"),
    addSbtPlugin("ch.epfl.scala" % "sbt-release-early" % "2.0.1+3-5257935d"),
    unmanagedSourceDirectories in Compile +=
      baseDirectory.value.getParentFile /  "sbt-bloop" / "src" / "main" / "scala",
    libraryDependencies ++= List(
      

    )
  )
