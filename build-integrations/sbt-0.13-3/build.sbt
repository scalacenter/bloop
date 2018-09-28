val Scalding = Integrations.Scalding
val SummingBird = Integrations.SummingBird
val integrations = List(Scalding)

ivyLoggingLevel in ThisBuild := UpdateLogging.Quiet

val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
  .enablePlugins(IntegrationPlugin)
  .settings(
    name := "bloop-integrations-build-2",
    enableIndexCreation := true,
    integrationIndex := {
      Map(
        "scalding" -> bloopConfigDir.in(Scalding).value
        //"summingbird" -> bloopConfigDir.in(SummingBird).value
      )
    },
    cleanAllBuilds := {
      // Do it sequentially, there seems to be a race condition in windows
      Def.sequential(
        cleanAllBuilds,
        clean.in(Scalding)
        //clean.in(SummingBird)
      ).value
    }
  )
