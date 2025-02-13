package bloop.cli

import scala.util.Properties

import dev.dirs.ProjectDirectories
import dev.dirs.jni.WindowsJni

trait Directories {
  def bloopDaemonDir: os.Path
  def bloopWorkingDir: os.Path
}

object Directories {

  final case class OsLocations(projDirs: ProjectDirectories) extends Directories {
    lazy val bloopDaemonDir: os.Path =
      bloopWorkingDir / "daemon"
    lazy val bloopWorkingDir: os.Path = {
      val baseDir =
        if (Properties.isMac) projDirs.cacheDir
        else projDirs.dataLocalDir
      os.Path(baseDir, os.pwd) / "bloop"
    }
  }

  final case class SubDir(dir: os.Path) extends Directories {
    lazy val bloopDaemonDir: os.Path =
      bloopWorkingDir / "daemon"
    lazy val bloopWorkingDir: os.Path =
      dir / "data-local" / "wd"
  }

  def default(): Directories = {
    OsLocations(ProjectDirectories.from(null, null, "ScalaCli", WindowsJni.getJdkAwareSupplier()))
  }

  def under(dir: os.Path): Directories =
    SubDir(dir)
}
