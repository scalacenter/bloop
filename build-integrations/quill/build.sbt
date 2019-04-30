val integrations = List(Integrations.Quill)
val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
  .settings(
    name := "bloop-quill-integration",
    enableIndexCreation := true,
    integrationIndex := {
      Map(
        "quill" -> bloopConfigDir.in(Integrations.Quill).in(Compile).value
      )
    },
    cleanAllBuilds := {
      Def.sequential(
        cleanAllBuilds,
        clean.in(Integrations.Quill)
      )
    }
  )
