val integrations = List(Integrations.Linkerd)
val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
  .settings(
    name := "bloop-linkerd-integration",
    enableIndexCreation := true,
    integrationIndex := {
      Map(
        "linkerd" -> bloopConfigDir.in(Integrations.Linkerd).in(Compile).value
      )
    },
    cleanAllBuilds := {
      Def.sequential(
        cleanAllBuilds,
        clean.in(Integrations.Linkerd)
      )
    }
  )
