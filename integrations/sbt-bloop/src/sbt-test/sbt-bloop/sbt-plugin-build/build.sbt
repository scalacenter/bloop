name := "sbt-plugin-foobar"
sbtPlugin := true

import java.nio.file.Files

val checkMetaBuildInfo = taskKey[Unit]("Check sbt meta build info is not generated")
checkMetaBuildInfo in ThisBuild := {
  def readBareFile(p: java.nio.file.Path): String =
    new String(Files.readAllBytes(p)).replaceAll("\\s", "")

  def check(f: File): Unit = {
    val metaContents = readBareFile(f.toPath)
    assert(metaContents.contains("\"autoImports\":[\""), "Missing auto imports.")
    assert(metaContents.contains("\"sbtVersion\":\""), "Missing sbt version.")
  }

  val bloopMetaDir = Keys.baseDirectory.value./("project")./(".bloop")
  val metaBuildConfig = bloopMetaDir./("sbt-plugin-build.json")
  check(metaBuildConfig)
}
