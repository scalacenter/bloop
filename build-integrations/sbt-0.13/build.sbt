val ApacheSpark = Integrations.ApacheSpark
val LihaoyiUtest = Integrations.LihaoyiUtest
val ScalaScala = Integrations.ScalaScala
val ScalaCenterVersions = Integrations.ScalaCenterVersions
val Scalameta = Integrations.Scalameta
val integrations = List(ApacheSpark, LihaoyiUtest, ScalaScala, ScalaCenterVersions, Scalameta)

val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
  .enablePlugins(IntegrationPlugin)
  .settings(
    name := "bloop-integrations-build",
    enableIndexCreation := true,
    integrationIndex := {
      Map(
        "spark" -> bloopConfigDir.in(ApacheSpark).value,
        "utest" -> bloopConfigDir.in(LihaoyiUtest).value,
        "scala" -> bloopConfigDir.in(ScalaScala).value,
        "scalameta" -> bloopConfigDir.in(Scalameta).value,
        "versions" -> bloopConfigDir.in(ScalaCenterVersions).value
      )
    },
    cleanAllBuilds := {
      // Do it sequentially, there seems to be a race condition in windows
      Def.sequential(
        cleanAllBuilds,
        clean.in(ApacheSpark),
        clean.in(LihaoyiUtest),
        clean.in(ScalaScala),
        clean.in(Scalameta),
        clean.in(ScalaCenterVersions)
      ).value
    }
  )
