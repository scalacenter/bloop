package bloop.bloop4j.core
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import bloop.config.Config
import java.nio.file.Path
import java.net.URI

case class BloopBuildTarget(id: BuildTargetIdentifier, configPath: Path, config: Config.File)

object BloopBuildTarget {
  def unsafeReadBuildTarget(bloopConfigPath: Path): BloopBuildTarget = {
    bloop.config.read(bloopConfigPath) match {
      case Left(value) => throw value
      case Right(configFile) =>
        val projectUri = deriveProjectUri(bloopConfigPath, configFile.project.name)
        val id = new BuildTargetIdentifier(projectUri.toString)
        BloopBuildTarget(id, bloopConfigPath, configFile)
    }
  }

  def unsafeCreateBuildTarget(
      bloopConfigDir: Path,
      configFile: Config.File
  ): BloopBuildTarget = {
    val projectName = configFile.project.name
    val configPath = bloopConfigDir.resolve(s"$projectName.json")
    val projectUri = deriveProjectUri(configPath, projectName)
    bloop.config.write(configFile, configPath)
    val id = new BuildTargetIdentifier(projectUri.toString)
    BloopBuildTarget(id, configPath, configFile)
  }

  def deriveProjectUri(projectBaseDir: Path, name: String): URI = {
    // This is the "idiomatic" way of adding a query to a URI in Java
    val existingUri = projectBaseDir.toUri
    new URI(
      existingUri.getScheme,
      existingUri.getUserInfo,
      existingUri.getHost,
      existingUri.getPort,
      existingUri.getPath,
      s"id=${name}",
      existingUri.getFragment
    )
  }

  case class ProjectId(name: String, dir: Path)
  def projectNameFrom(btid: BuildTargetIdentifier): ProjectId = {
    val existingUri = new URI(btid.getUri)
    val uriWithNoQuery = new URI(
      existingUri.getScheme,
      existingUri.getUserInfo,
      existingUri.getHost,
      existingUri.getPort,
      existingUri.getPath,
      null,
      existingUri.getFragment
    )

    val name = existingUri.getQuery().stripPrefix("id=")
    val projectDir = java.nio.file.Paths.get(uriWithNoQuery)
    ProjectId(name, projectDir)
  }

}
