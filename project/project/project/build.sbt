addSbtPlugin("io.get-coursier" % "sbt-shading" % "2.0.0-RC3-3")
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "2.0.0-RC3-3")

// Make sure we filter out Twitter's jarjar dep and use our patched version
excludeDependencies ++= List(sbt.ExclusionRule("org.pantsbuild", "jarjar"))
libraryDependencies ++= List(
  "ch.epfl.scala" % "jarjar" % "1.7.2-patched"
)
