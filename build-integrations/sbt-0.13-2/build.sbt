val Lichess = Integrations.Lichess
val integrations = List(Lichess)

val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
  .enablePlugins(IntegrationPlugin)
  .settings(
    name := "bloop-integrations-build-2",
    enableIndexCreation := true,
    integrationIndex := {
      Map(
        "lichess" -> bloopConfigDir.in(Lichess).value
      )
    },
    cleanAllBuilds := {
      // Do it sequentially, there seems to be a race condition in windows
      Def.sequential(
        cleanAllBuilds,
        clean.in(Lichess)
      ).value
    }
  )
