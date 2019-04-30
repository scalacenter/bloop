val integrations = List(Integrations.Http4s)
val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
  .settings(
    name := "bloop-http4s-integration",
    enableIndexCreation := true,
    integrationIndex := {
      Map(
        "Http4s" -> bloopConfigDir.in(Integrations.Http4s).in(Compile).value
      )
    },
    cleanAllBuilds := {
      Def
        .sequential(
          cleanAllBuilds,
          clean.in(Integrations.Http4s)
        )
        .value
    }
  )
