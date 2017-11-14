package bloop

import java.io.File
import java.nio.file.{Files, Path}

class ComponentProvider(val baseDir: Path) extends xsbti.ComponentProvider {
  private[this] val componentsBaseDir = baseDir.resolve("components")
  Files.createDirectories(componentsBaseDir)

  override val lockFile: File = baseDir.resolve("lockfile").toFile

  override def addToComponent(componentID: String, components: Array[File]): Boolean =
    GlobalLock(lockFile) {
      val location = componentLocation(componentID).toPath
      if (components.forall(_.isFile)) {
        copyFiles(components, location)
        true
      } else {
        false
      }
    }

  override def component(componentID: String): Array[File] =
    GlobalLock(lockFile) {
      Option(componentLocation(componentID).listFiles)
        .getOrElse(Array.empty)
        .filter(_.isFile)
    }

  override def componentLocation(componentID: String): File =
    componentsBaseDir.resolve(componentID).toFile

  override def defineComponent(componentID: String, components: Array[File]): Unit =
    GlobalLock(lockFile) {
      val location = componentsBaseDir.resolve(componentID)
      Files.createDirectory(location)
      copyFiles(components, location)
    }

  private[this] def copyFiles(files: Array[File], target: Path): Unit =
    files.foreach { f =>
      Files.copy(f.toPath, target.resolve(f.getName))
    }

}

class UnknownComponentException(componentID: String)
    extends Exception(s"Unknown component: $componentID")
