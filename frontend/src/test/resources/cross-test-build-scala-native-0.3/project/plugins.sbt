addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "0.6.0")
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.3.9")

addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "1.4.11-9-827a32e7")
updateOptions := updateOptions.value.withLatestSnapshots(false)
