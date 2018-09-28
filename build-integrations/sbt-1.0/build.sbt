val SbtSbt = Integrations.SbtSbt
val GuardianFrontend = Integrations.GuardianFrontend
val MiniBetterFiles = Integrations.MiniBetterFiles
val WithResources = Integrations.WithResources
val WithTests = Integrations.WithTests
val AkkaAkka = Integrations.AkkaAkka
val CrossPlatform = Integrations.CrossPlatform
val Scalatra = Integrations.Scalatra
val Finagle = Integrations.Finagle
val Algebird = Integrations.Algebird

val DefaultProjects = List(SbtSbt, GuardianFrontend, MiniBetterFiles, WithResources, WithTests, AkkaAkka, CrossPlatform, Scalatra, Algebird)
val TwitterProjects = List(Finagle)

import java.util.Locale
val isWindows = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")
val integrations = if (isWindows) DefaultProjects else DefaultProjects ::: TwitterProjects

bloopExportJarClassifiers in WithTests := Some(Set("sources"))

val linuxSpecificProjects = Def.settingDyn {
  if (isWindows) Def.setting(Map.empty)
  else {
    Def.setting {
      Map(
        "finagle" -> bloopConfigDir.in(Finagle).in(Compile).value,
      )
    }
  }
}

import bloop.build.integrations.PluginKeys
val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
  .settings(
    name := "bloop-integrations-build",
    enableIndexCreation := true,
    integrationIndex := {
      Map(
        "sbt" -> bloopConfigDir.in(SbtSbt).in(Compile).value,
        "scalatra" -> bloopConfigDir.in(Scalatra).in(Compile).value,
        "frontend" -> bloopConfigDir.in(GuardianFrontend).in(Compile).value,
        "mini-better-files" -> bloopConfigDir.in(MiniBetterFiles).in(Compile).value,
        "with-resources" -> bloopConfigDir.in(WithResources).in(Compile).value,
        "with-tests" -> bloopConfigDir.in(WithTests).in(Compile).value,
        "akka" -> bloopConfigDir.in(AkkaAkka).in(Compile).value,
        "cross-platform" -> bloopConfigDir.in(CrossPlatform).in(Compile).value,
        "algebird" -> bloopConfigDir.in(Algebird).in(Compile).value,
      ) ++ (if (isWindows) Map.empty else linuxSpecificProjects.value)
    },
    cleanAllBuilds := {
      // Do it sequentially, there seems to be a race condition in windows
      val twitterProjectsClean = Def.taskDyn {
        if (isWindows) Def.task(())
        else clean.in(Finagle)
      }

      Def.sequential(
        cleanAllBuilds,
        clean.in(SbtSbt),
        clean.in(Scalatra),
        clean.in(GuardianFrontend),
        clean.in(MiniBetterFiles),
        clean.in(WithResources),
        clean.in(WithTests),
        clean.in(AkkaAkka),
        clean.in(CrossPlatform),
        clean.in(Algebird),
        twitterProjectsClean
      )
    }
  )
