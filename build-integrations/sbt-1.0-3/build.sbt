val GuardianGrid = Integrations.GuardianGrid

val integrations = List(GuardianGrid)

val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
  .settings(
    name := "bloop-integrations-build",
    enableIndexCreation := true,
    integrationIndex := {
      Map(
        "grid" -> bloopConfigDir.in(GuardianGrid).in(Compile).value,
      )
    },
    cleanAllBuilds := {
      Def.sequential(
        cleanAllBuilds,
        clean.in(GuardianGrid)
      )
    }
  )
