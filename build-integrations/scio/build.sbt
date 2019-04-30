val integrations = List(Integrations.Scio)
val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
  .settings(
    name := "bloop-scio-integration",
    enableIndexCreation := true,
    integrationIndex := {
      Map(
        "scio" -> bloopConfigDir.in(Integrations.Scio).in(Compile).value
      )
    },
    cleanAllBuilds := {
      Def
        .sequential(
          cleanAllBuilds,
          clean.in(Integrations.Scio)
        )
        .value
    }
  )
