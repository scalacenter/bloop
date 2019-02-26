package bloop.data

import bloop.io.AbsolutePath

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
  case class CliClientInfo(
      id: String
  ) extends ClientInfo {
    def getUniqueClassesDirFor(project: Project): AbsolutePath = {
      // CLI clients use the classes directory from the project, that's why
      // we don't support concurrent CLI client executions for the same build
      AbsolutePath(Files.createDirectories(project.classesDir.underlying).toRealPath())
    }
  }

  case class BspClientInfo(
      name: String,
      version: String,
      bspVersion: String
  ) extends ClientInfo {
    import java.util.UUID
    import java.util.concurrent.ConcurrentHashMap
    private val uniqueDirs = new ConcurrentHashMap[Project, AbsolutePath]()
    def getUniqueClassesDirFor(project: Project): AbsolutePath = {
      uniqueDirs.computeIfAbsent(
        project,
        (project: Project) => {
          val classesDir = project.classesDir.underlying
          val parentDir = classesDir.getParent()
          val classesName = classesDir.getFileName()
          val newClassesName = s"${classesName}-${this.name}-${UUID.randomUUID}"
          AbsolutePath(
            Files.createDirectories(parentDir.resolve(newClassesName)).toRealPath()
          )
        }
      )
    }
  }
}
