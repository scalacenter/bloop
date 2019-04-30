val integrations = List(Integrations.Cats)
val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
  .settings(
    name := "bloop-cats-integration",
    enableIndexCreation := true,
    integrationIndex := {
      Map(
        "cats" -> bloopConfigDir.in(Integrations.Cats).in(Compile).value
      )
    },
    cleanAllBuilds := {
      Def.sequential(
        cleanAllBuilds,
        clean.in(Integrations.Cats)
      )
    }
  )
