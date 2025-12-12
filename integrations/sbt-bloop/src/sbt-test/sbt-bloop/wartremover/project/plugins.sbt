addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % sys.props.apply("plugin.version"))

addSbtPlugin("org.wartremover" % "sbt-wartremover" % {
  if (sys.props.get("scalaVersion").get.startsWith("2")) "3.1.8" else "3.4.3"
})
