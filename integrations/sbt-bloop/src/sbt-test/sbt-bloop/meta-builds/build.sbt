val foo = project.in(file("."))
val bar = project

import java.nio.file.Files

val checkMetaBuildInfo = taskKey[Unit]("Check sbt meta build info")
checkMetaBuildInfo := {
  // Bare settings apply to every project under sbt 2. Only the build root owns `project/`.
  val buildBase = Keys.baseDirectory.value
  val metaProject = buildBase / "project"
  if ((metaProject / "plugins.sbt").isFile) {
    def readBareFile(p: java.nio.file.Path): String =
      new String(Files.readAllBytes(p)).replaceAll("\\s", "")

    def check(f: File): Unit = {
      val metaContents = readBareFile(f.toPath)
      assert(metaContents.contains("\"autoImports\":[\""), s"Missing auto imports in $f")
      assert(metaContents.contains("\"sbtVersion\":\""), s"Missing sbt version in $f")
    }

    val metaBuildConfig = metaProject / ".bloop" / s"${buildBase.getName}-build.json"
    check(metaBuildConfig)
  }
}

val checkSourceJars = taskKey[Unit]("Check source jars are resolved and persisted")
checkSourceJars := {
  val buildBase = Keys.baseDirectory.value
  val metaProject = buildBase / "project"
  if ((metaProject / "plugins.sbt").isFile) {
    def readBareFile(p: java.nio.file.Path): String =
      new String(Files.readAllBytes(p)).replaceAll("\\s", "")

    val metaBuildConfig = metaProject / ".bloop" / s"${buildBase.getName}-build.json"
    val contents = readBareFile(metaBuildConfig.toPath)

    val buildInfoSourceArtifact =
      for {
        cfg <- bloop.config.read(metaBuildConfig.toPath).toOption
        resolution <- cfg.project.resolution
        // sbt 2 reports the cross-built plugin name (`sbt-buildinfo_sbt2_3`).
        module <- resolution.modules.find(_.name.startsWith("sbt-buildinfo"))
        sourceArtifact <- module.artifacts.find(_.classifier.contains("sources"))
      } yield sourceArtifact

    assert(buildInfoSourceArtifact.isDefined, "Source artifact for sbt-buildinfo was not found")
  }
}
