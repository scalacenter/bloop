val foo = project.in(file("."))
val bar = project

import java.nio.file.Files

val checkMetaBuildInfo = taskKey[Unit]("Check sbt meta build info")
checkMetaBuildInfo in ThisBuild := {
  def readBareFile(p: java.nio.file.Path): String =
    new String(Files.readAllBytes(p)).replaceAll("\\s", "")

  def check(f: File): Unit = {
    val metaContents = readBareFile(f.toPath)
    assert(metaContents.contains("\"autoImports\":[\""), "Missing auto imports.")
    assert(metaContents.contains("\"sbtVersion\":\""), "Missing sbt version.")
  }

  val bloopMetaDir = Keys.baseDirectory.value./("project")./(".bloop")
  val metaBuildConfig = bloopMetaDir./("meta-builds-build.json")
  check(metaBuildConfig)
}

val checkSourceJars = taskKey[Unit]("Check source jars are resolved and persisted")
checkSourceJars in ThisBuild := {
  def readBareFile(p: java.nio.file.Path): String =
    new String(Files.readAllBytes(p)).replaceAll("\\s", "")

  val bloopDir = Keys.baseDirectory.value./("project")./(".bloop")
  val metaBuildConfig = bloopDir./("meta-builds-build.json")
  val metaBuildTestConfig = bloopDir./("meta-builds-build-test.json")
  val contents = readBareFile(metaBuildConfig.toPath)
  assert(contents.contains("\"classifier\":\"sources\""), "Missing source jar.")
}
