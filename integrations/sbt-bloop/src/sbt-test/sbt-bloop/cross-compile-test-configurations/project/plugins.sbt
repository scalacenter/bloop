// Sbt classloads jawn 0.8.0 so let's use a parser that doesn't use a bin incompatible version
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % sys.props.apply("plugin.version"))
libraryDependencies ++= List("io.circe" %% "circe-jackson29" % "0.9.0")
