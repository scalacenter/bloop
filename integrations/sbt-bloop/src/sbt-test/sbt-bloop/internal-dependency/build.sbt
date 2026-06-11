import bloop.integrations.sbt.BloopDefaults

val util = project
val core = project.dependsOn(util % "compile-internal, test-internal")

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
ThisBuild / checkBloopFiles := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")

  def readConfig(name: String): bloop.config.Config.File =
    BloopDefaults.unsafeParseConfig(bloopDir./(s"$name.json").toPath)

  val coreConfig = readConfig("core")
  assert(
    coreConfig.project.dependencies == List("util"),
    s"Expected internal dependency util in core, found ${coreConfig.project.dependencies}"
  )

  val utilClassesDir = readConfig("util").project.classesDir
  assert(
    coreConfig.project.classpath.contains(utilClassesDir),
    s"Expected util classes dir on the core classpath, found ${coreConfig.project.classpath}"
  )

  val coreTestConfig = readConfig("core-test")
  assert(
    coreTestConfig.project.dependencies.sorted == List("core", "util"),
    s"Expected internal dependency util in core-test, found ${coreTestConfig.project.dependencies}"
  )
}
