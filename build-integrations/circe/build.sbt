val integrations = List(Integrations.Circe)
val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
  .settings(
    name := "bloop-circe-integration",
    enableIndexCreation := true,
    integrationIndex := {
      Map(
        "circe" -> bloopConfigDir.in(Integrations.Circe).in(Compile).value
      )
    },
    cleanAllBuilds := {
      Def.sequential(
        cleanAllBuilds,
        clean.in(Integrations.Circe)
      )
    }
  )
