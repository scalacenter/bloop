addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "0.6.1")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.0.1")

addSbtPlugin("ch.epfl.scala" % "sbt-bloop-build" % "1.0.0-SNAPSHOT")
updateOptions := updateOptions.value.withLatestSnapshots(false)
