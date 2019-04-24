package bloop.data

import bloop.io.AbsolutePath
import bloop.util.UUIDUtil

import java.nio.file.Files

sealed trait ClientInfo {

  /**
   * Provides the classes directory that should be used to compile
   * a given project to. This information is client-specific because
   * clients are assigned unique, different classes directory to
   * isolate the side-effects of concurrent clients over otherwise
   * shared global classes directories.
   */
  def getUniqueClassesDirFor(project: Project): AbsolutePath
}

object ClientInfo {
  final case class CliClientInfo(
      id: String
  ) extends ClientInfo {
    def getUniqueClassesDirFor(project: Project): AbsolutePath = {
      // CLI clients use the classes directory from the project, that's why
      // we don't support concurrent CLI client executions for the same build
      AbsolutePath(Files.createDirectories(project.genericClassesDir.underlying).toRealPath())
    }

    override def toString(): String = s"cli client '$id'"
  }

  final case class BspClientInfo(
      name: String,
      version: String,
      bspVersion: String
  ) extends ClientInfo {
    val uniqueId: String = s"${this.name}-${UUIDUtil.randomUUID}"
    import java.util.concurrent.ConcurrentHashMap
    private val uniqueDirs = new ConcurrentHashMap[Project, AbsolutePath]()
    def getUniqueClassesDirFor(project: Project): AbsolutePath = {
      uniqueDirs.computeIfAbsent(
        project,
        (project: Project) => {
          val classesDir = project.genericClassesDir.underlying
          val parentDir = classesDir.getParent()
          val classesName = classesDir.getFileName()
          val newClassesName = s"${classesName}-${uniqueId}"
          AbsolutePath(
            Files.createDirectories(parentDir.resolve(newClassesName)).toRealPath()
          )
        }
      )
    }

    override def toString(): String = s"bsp client '$name $version'"
  }
}
