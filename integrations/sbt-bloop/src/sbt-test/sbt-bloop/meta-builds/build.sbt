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

  val directoryName = Keys.baseDirectory.value.getName()
  val bloopMetaDir = Keys.baseDirectory.value./("project")./(".bloop")
  val metaBuildConfig = bloopMetaDir./(s"$directoryName-build.json")
  check(metaBuildConfig)
}

val checkSourceJars = taskKey[Unit]("Check source jars are resolved and persisted")
checkSourceJars in ThisBuild := {
  val directoryName = Keys.baseDirectory.value.getName()
  def readBareFile(p: java.nio.file.Path): String =
    new String(Files.readAllBytes(p)).replaceAll("\\s", "")

  val bloopDir = Keys.baseDirectory.value./("project")./(".bloop")
  val metaBuildConfig = bloopDir./(s"$directoryName-build.json")
  val contents = readBareFile(metaBuildConfig.toPath)

  val buildInfoSourceArtifact =
    for {
      cfg <- bloop.config.read(metaBuildConfig.toPath).toOption
      resolution <- cfg.project.resolution
      module <- resolution.modules.find(_.name == "sbt-buildinfo")
      sourceArtifact <- module.artifacts.find(_.classifier.contains("sources"))
    } yield sourceArtifact

  assert(buildInfoSourceArtifact.isDefined, "Source artifact for sbt-buildinfo was not found")
}
