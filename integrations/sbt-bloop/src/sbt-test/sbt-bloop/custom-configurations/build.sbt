import bloop.integrations.sbt.BloopDefaults

val foo = project.in(file("."))
  .configs(IntegrationTest)
  .settings(inConfig(IntegrationTest)(Defaults.itSettings))
  .settings(inConfig(IntegrationTest)(BloopDefaults.configSettings))
