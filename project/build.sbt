val mvnVersion = "3.5.2"
val root = project
  .in(file("."))
  .settings(
    scalaVersion := "2.12.4",
    addSbtPlugin("com.lucidchart" % "sbt-scalafmt" % "1.14"),
    addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0"),
    addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.2.27"),
    addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6"),
    addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3"),
    addSbtPlugin("ch.epfl.scala" % "sbt-release-early" % "2.0.1+3-5257935d"),
    // Let's add our sbt plugin to the sbt too ;)
    unmanagedSourceDirectories in Compile ++= {
      val mainDir = baseDirectory.value.getParentFile /  "sbt-bloop" / "src" / "main"
      List(mainDir / "scala", mainDir / s"scala-sbt-${Keys.sbtBinaryVersion.value}")
    },
    // We need to add libdeps for the maven integration plugin to work
    libraryDependencies ++= List(
      "org.apache.maven.plugin-tools" % "maven-plugin-tools-api" % "3.5",
      "org.apache.maven.plugin-tools" % "maven-plugin-annotations" % "3.5",
      "org.apache.maven.plugin-tools" % "maven-plugin-tools-generators" % "3.5",
      "org.apache.maven.plugin-tools" % "maven-plugin-tools-annotations" % "3.5",

      "org.apache.maven" % "maven-core" % mvnVersion,
      "org.apache.maven" % "maven-plugin-api" % mvnVersion,
      "org.apache.maven" % "maven-model-builder" % mvnVersion,
    ),
    // 5 hours to find that this had to be overridden because conflicted with sbt-pom-reader
    dependencyOverrides ++= List("org.apache.maven" % "maven-settings" % mvnVersion)
  )
