package bloop.data

import bloop.io.AbsolutePath
import bloop.util.UUIDUtil

import java.nio.file.Files

sealed trait ClientInfo {

  /**
   * Returns the connection timestamp that was registered the first time the
   * client established a connection with the build server.
   */
  def getConnectionTimestamp: Long

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
    private val connectionTimestamp = System.currentTimeMillis()
    def getConnectionTimestamp: Long = connectionTimestamp

    def getUniqueClassesDirFor(project: Project): AbsolutePath = {
      // CLI clients use the classes directory from the project, that's why
      // we don't support concurrent CLI client executions for the same build
      AbsolutePath(Files.createDirectories(project.genericClassesDir.underlying).toRealPath())
    }

    override def toString(): String =
      s"cli client '$id' (since ${activeSinceMillis(connectionTimestamp)})"
  }

  final case class BspClientInfo(
      name: String,
      version: String,
      bspVersion: String
  ) extends ClientInfo {
    private val connectionTimestamp = System.currentTimeMillis()
    def getConnectionTimestamp: Long = connectionTimestamp

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

    override def toString(): String =
      s"bsp client '$name $version' (since ${activeSinceMillis(connectionTimestamp)})"
  }

  def activeSinceMillis(startMs: Long) = {
    import java.time.{Instant, Duration}
    val start = Instant.ofEpochMilli(startMs)
    val now = Instant.ofEpochMilli(System.currentTimeMillis())
    val duration = Duration.between(start, now)
    // https://stackoverflow.com/questions/3471397/how-can-i-pretty-print-a-duration-in-java
    duration.toString().substring(2).replaceAll("(\\d[HMS])(?!$)", "$1 ").toLowerCase
  }
}
