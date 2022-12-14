addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.2.0")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.3.1")

addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "1.4.11-9-827a32e7")
updateOptions := updateOptions.value.withLatestSnapshots(false)
