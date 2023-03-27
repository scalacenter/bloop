val pluginVersion = sys.props.getOrElse(
  "bloopVersion",
  throw new RuntimeException("Unable to find -DbloopVersion")
)

addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % pluginVersion)

updateOptions := updateOptions.value.withLatestSnapshots(false)
