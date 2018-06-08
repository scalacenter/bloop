val foo = project.in(file("."))
val bar = project

import java.nio.file.Files
val checkSourceAndDocs = taskKey[Unit]("Check source and doc jars are resolved and persisted")
checkSourceAndDocs in ThisBuild := {
  def readBareFile(p: java.nio.file.Path): String =
    new String(Files.readAllBytes(p)).replaceAll("\\s", "")

  val bloopDir = Keys.baseDirectory.value./("project")./(".bloop")
  val metaBuildConfig = bloopDir./("meta-builds-build.json")
  val metaBuildTestConfig = bloopDir./("meta-builds-build-test.json")
  val contents = readBareFile(metaBuildConfig.toPath)
  assert(contents.contains("\"classifier\":\"sources\""), "Missing source jar.")
  assert(contents.contains("\"classifier\":\"javadoc\""), "Missing doc jar.")
}
