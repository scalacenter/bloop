addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % sys.props.apply("plugin.version"))

val wartremoverVersion =
  if (sys.props.get("scalaVersion").exists(_.startsWith("2.12"))) "3.4.3"
  else "3.5.8"

addSbtPlugin("org.wartremover" % "sbt-wartremover" % wartremoverVersion)
