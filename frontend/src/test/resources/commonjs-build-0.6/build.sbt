bloopConfigDir in Global := baseDirectory.value / "bloop-config"

enablePlugins(ScalaJSPlugin)

name := "commonjs-project"
scalaVersion := "2.11.12"
scalacOptions += "-Ywarn-unused"
scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
