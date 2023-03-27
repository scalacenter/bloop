addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "0.6.1")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "0.6.26")

val pluginVersion = sys.props.getOrElse(
  "bloopVersion",
  throw new RuntimeException("Unable to find -DbloopVersion")
)

addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % pluginVersion)

updateOptions := updateOptions.value.withLatestSnapshots(false)
