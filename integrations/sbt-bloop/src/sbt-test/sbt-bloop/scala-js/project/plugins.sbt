addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % sys.props.apply("plugin.version"))
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "0.6.28")
libraryDependencies += "org.scala-js" %% "scalajs-env-jsdom-nodejs" % "1.1.0"
