val mvnVersion = "3.5.2"
val mvnPluginToolsVersion = "3.5"
val typesafeConfig = "com.typesafe" % "config" % "1.3.2"
val metaconfigCore = "com.geirsson" %% "metaconfig-core" % "0.6.0"
val metaconfigConfig = "com.geirsson" %% "metaconfig-typesafe-config" % "0.6.0"
val metaconfigDocs = "com.geirsson" %% "metaconfig-docs" % "0.6.0"
val circeDerivation = "io.circe" %% "circe-derivation" % "0.9.0-M3"

val root = project
  .in(file("."))
  .settings(
    scalaVersion := "2.12.4",
    // Force new version of sbt-dynver in the sbt universe (classlaoders are not isolated)
    addSbtPlugin("com.dwijnand" % "sbt-dynver" % "2.1.0"),
    addSbtPlugin("ohnosequences" % "sbt-github-release" % "0.6.0"),
    addSbtPlugin("com.lucidchart" % "sbt-scalafmt" % "1.14"),
    addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0"),
    addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.2.27"),
    addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6"),
    addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3"),
    addSbtPlugin("ch.epfl.scala" % "sbt-release-early" % "2.1.1"),
    addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "1.3.1"),
    addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.6.2"),
    addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.1"),
    libraryDependencies += { "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value },
    // Let's add our sbt plugin to the sbt too ;)
    unmanagedSourceDirectories in Compile ++= {
      val baseDir = baseDirectory.value.getParentFile
      val pluginMainDir = baseDir / "integrations" / "sbt-bloop" / "src" / "main"
      List(
        baseDir / "config" / "src" / "main" / "scala",
        baseDir / "config" / "src" / "main" / s"scala-${Keys.scalaBinaryVersion.value}",
        pluginMainDir / "scala",
        pluginMainDir / s"scala-sbt-${Keys.sbtBinaryVersion.value}"
      )
    },
    // We need to add libdeps for the maven integration plugin to work
    libraryDependencies ++= List(
      "org.apache.maven.plugin-tools" % "maven-plugin-tools-api" % mvnPluginToolsVersion,
      "org.apache.maven.plugin-tools" % "maven-plugin-annotations" % mvnPluginToolsVersion,
      "org.apache.maven.plugin-tools" % "maven-plugin-tools-generators" % mvnPluginToolsVersion,
      "org.apache.maven.plugin-tools" % "maven-plugin-tools-annotations" % mvnPluginToolsVersion,
      "org.apache.maven" % "maven-core" % mvnVersion,
      "org.apache.maven" % "maven-plugin-api" % mvnVersion,
      "org.apache.maven" % "maven-model-builder" % mvnVersion,
      "commons-codec" % "commons-codec" % "1.11"
    ),
    libraryDependencies ++= List(
      typesafeConfig,
      metaconfigCore,
      metaconfigDocs,
      metaconfigConfig,
      circeDerivation
    ),
    // 5 hours to find that this had to be overridden because conflicted with sbt-pom-reader
    dependencyOverrides ++= List("org.apache.maven" % "maven-settings" % mvnVersion)
  )
