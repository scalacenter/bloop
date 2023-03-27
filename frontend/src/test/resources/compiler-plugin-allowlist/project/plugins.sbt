addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "0.6.1")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "0.6.28")
addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.3.7")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.1")
addSbtPlugin("com.softwaremill.clippy" % "plugin-sbt" % "0.5.3")

val pluginVersion = sys.props.getOrElse(
  "bloopVersion",
  throw new RuntimeException("Unable to find -DbloopVersion")
)

addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % pluginVersion)

updateOptions := updateOptions.value.withLatestSnapshots(false)
