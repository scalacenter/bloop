addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "0.6.1")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "0.6.28")

addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % sys.props.apply("plugin.version"))
