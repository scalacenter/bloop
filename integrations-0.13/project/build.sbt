val ApacheSpark = RootProject(
  uri("git://github.com/scalacenter/spark.git#4e037d614250b855915c28ac1e84471075293124"))
val LihaoyiUtest = RootProject(
  uri("git://github.com/lihaoyi/utest.git#b5440d588d5b32c85f6e9392c63edd3d3fed3106"))
val ScalaScala = RootProject(
  uri("git://github.com/scalacenter/scala.git#6809ff601ed2efc81d3d0a199592f178429bf2ec"))
val ScalaCenterVersions = RootProject(
  uri("git://github.com/scalacenter/versions.git#c296028a33b06ba3a41d399d77c21f6b7100c001"))

val allIntegrationProjects = List(ApacheSpark, LihaoyiUtest, ScalaScala, ScalaCenterVersions)

// No changes should be needed below this point

val dummy = project.aggregate(allIntegrationProjects: _*)
onLoad in Global := Def.settingDyn {
  val settings = allIntegrationProjects.map { p =>
    Def.setting { build.BuildPlugin.writeAddSbtPlugin((baseDirectory in p).value) }
  }
  val orig = (onLoad in Global).value
  Def.setting {
    val _ = settings.join.value
    orig
  }
}.value
