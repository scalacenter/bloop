package bloop.launcher.util

import java.nio.file.{Files, Path, Paths}

import io.github.soc.directories.ProjectDirectories

object Environment {
  final val isWindows: Boolean = scala.util.Properties.isWin
  final val projectDirectories: ProjectDirectories =
    ProjectDirectories.from("", "", "bloop")
  final val bloopDataLogsDir: Path =
    Files.createDirectories(Paths.get(projectDirectories.dataDir).resolve("logs"))

  def cwd: Path = Paths.get(System.getProperty("user.dir"))
  def homeDirectory: Path = Paths.get(System.getProperty("user.home"))
  def defaultBloopDirectory: Path = homeDirectory.resolve(".bloop")
}
