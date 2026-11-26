addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.23.0")
addSbtPlugin("org.wartremover" % "sbt-wartremover" % "3.6.3")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.10")

val pluginVersion = sys.props.getOrElse(
  "bloopVersion",
  throw new RuntimeException("Unable to find -DbloopVersion")
)

addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % pluginVersion)

updateOptions := updateOptions.value.withLatestSnapshots(false)
