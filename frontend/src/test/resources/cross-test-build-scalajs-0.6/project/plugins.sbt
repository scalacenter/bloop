addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "0.6.1")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "0.6.26")

addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "1.4.11-9-827a32e7")
updateOptions := updateOptions.value.withLatestSnapshots(false)
