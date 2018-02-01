val ApacheSpark = build.BuildPlugin.ApacheSpark
val LihaoyiUtest = build.BuildPlugin.LihaoyiUtest
val ScalaScala = build.BuildPlugin.ScalaScala
val ScalaCenterVersions = build.BuildPlugin.ScalaCenterVersions

val dummy = project.dependsOn(ApacheSpark, LihaoyiUtest, ScalaScala, ScalaCenterVersions)

onLoad in Global := {
  build.BuildPlugin.writeAddSbtPlugin((baseDirectory in ApacheSpark).value)
  build.BuildPlugin.writeAddSbtPlugin((baseDirectory in LihaoyiUtest).value)
  build.BuildPlugin.writeAddSbtPlugin((baseDirectory in ScalaScala).value)
  build.BuildPlugin.writeAddSbtPlugin((baseDirectory in ScalaCenterVersions).value)
  (onLoad in Global).value
}
