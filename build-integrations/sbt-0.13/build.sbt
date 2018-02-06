val ApacheSpark = RootProject(
  uri("git://github.com/scalacenter/spark.git#4e037d614250b855915c28ac1e84471075293124"))
val LihaoyiUtest = RootProject(
  uri("git://github.com/lihaoyi/utest.git#b5440d588d5b32c85f6e9392c63edd3d3fed3106"))
val ScalaScala = RootProject(
  uri("git://github.com/scala/scala.git#d1b745c2e97cc89e5d26b8f5a5696a2611c01af7"))
val ScalaCenterVersions = RootProject(
  uri("git://github.com/scalacenter/versions.git#c296028a33b06ba3a41d399d77c21f6b7100c001"))
val integrations = List(ApacheSpark, LihaoyiUtest, ScalaScala)//ScalaCenterVersions)

val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)

/*onLoad in Global := {
  val injectBloop = (state: State) => {
    "set every "
    ???
  }

  val previous = (onLoad in Global).value
  val isBloopInjected = (bloopGenerate in ApacheSpark).?.value.isDefined
  if (isBloopInjected) previous
  else injectBloop.compose(previous)
}*/
