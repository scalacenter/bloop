import bloop.integrations.sbt.BloopDefaults

val foo = project
  .in(file(".") / "foo")
  .settings(
    scalaCompilerBridgeBinaryJar := Some(baseDirectory.value / "fake.jar")
  )

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles := {
  // only check root for cross compat
  if (Keys.name.value != "foo") {
    val bloopDir = Keys.baseDirectory.value./(".bloop")
    val fooConfig = bloopDir./("foo.json")
    val config = BloopDefaults.unsafeParseConfig(fooConfig.toPath)
    val bridgeJars = config.project.scala.get.bridgeJars.map(_.map(_.toString))
    val expectedJars = Some(List((Keys.baseDirectory.value / "foo" / "fake.jar").toString))
    assert(bridgeJars == expectedJars)
  }
}
