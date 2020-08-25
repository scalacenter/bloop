addSbtPlugin("ch.epfl.scala" % "sbt-bloop-build-shaded" % "1.0.0-SNAPSHOT")
updateOptions := updateOptions.value.withLatestSnapshots(false)
