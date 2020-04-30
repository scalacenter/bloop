package bloop.data

import bloop.io.AbsolutePath
import bloop.io.Filenames
import bloop.util.UUIDUtil

import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.io.PrintStream
import monix.eval.Task
import scala.collection.mutable
import java.nio.file.Path
import bloop.io.Paths
import java.io.IOException
import monix.execution.misc.NonFatal
import bloop.logging.Logger
import bloop.tracing.BraveTracer
import bloop.logging.DebugFilter
import java.nio.file.NoSuchFileException
import bloop.cli.CommonOptions
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

sealed trait ClientInfo {

  /**
   * Returns true if the client is currently connected to the server, otherwise
   * false. This entrypoint is important for debugging purposes and managing
   * some state related to BSP clients.
   */
  def hasAnActiveConnection: Boolean

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
  def getUniqueClassesDirFor(project: Project, forceGeneration: Boolean): AbsolutePath

  /**
   * Tells the caller whether this client manages its own client classes
   * directories or whether bloop should take care of any created resources.
   */
  def hasManagedClassesDirectories: Boolean

  def refreshProjectsCommand: Option[List[String]]
}

object ClientInfo {
  final case class CliClientInfo(
      useStableCliDirs: Boolean,
      private val isConnected: () => Boolean
  ) extends ClientInfo {
    val id: String = CliClientInfo.generateDirName(useStableCliDirs)

    def hasAnActiveConnection: Boolean = isConnected()
    private val connectionTimestamp = System.currentTimeMillis()
    def hasManagedClassesDirectories: Boolean = false
    def getConnectionTimestamp: Long = connectionTimestamp

    private[ClientInfo] val freshCliDirs = new ConcurrentHashMap[Project, AbsolutePath]()
    def getUniqueClassesDirFor(project: Project, forceGeneration: Boolean): AbsolutePath = {
      // If the unique classes dir changes, change how outdated CLI dirs are cleaned up in [[Cli]]
      val newClientDir = {
        if (useStableCliDirs) {
          val classesDir = project.genericClassesDir.underlying
          val classesDirName = classesDir.getFileName()
          val projectDirName = s"$classesDirName-$id"
          project.clientClassesRootDirectory.resolve(projectDirName)
        } else {
          freshCliDirs.computeIfAbsent(
            project,
            _ => {
              val classesDir = project.genericClassesDir.underlying
              val classesDirName = classesDir.getFileName()
              val projectDirName = s"$classesDirName-$id"
              project.clientClassesRootDirectory.resolve(projectDirName)
            }
          )
        }
      }

      AbsolutePath(
        if (!forceGeneration) newClientDir.underlying
        else Files.createDirectories(newClientDir.underlying).toRealPath()
      )
    }

    private[bloop] def getCreatedCliDirectories: List[AbsolutePath] = {
      import scala.collection.JavaConverters._
      freshCliDirs.values().asScala.toList
    }

    override def toString(): String =
      s"cli client '$id' (since ${activeSinceMillis(connectionTimestamp)})"

    def refreshProjectsCommand: Option[List[String]] = None
  }

  object CliClientInfo {
    private val id: String = "bloop-cli"

    def generateDirName(useStableName: Boolean): String =
      if (useStableName) id
      else s"${CliClientInfo.id}-${UUIDUtil.randomUUID}"

    def isStableDirName(dirName: String): Boolean =
      dirName.endsWith("-" + id)
  }

  final case class BspClientInfo(
      name: String,
      version: String,
      bspVersion: String,
      ownsBuildFiles: Boolean,
      bspClientClassesRootDir: Option[AbsolutePath],
      refreshProjectsCommand: Option[List[String]],
      private val isConnected: () => Boolean
  ) extends ClientInfo {
    // The format of this unique id is used in `toGenericClassesDir`
    val uniqueId: String = s"${this.name}-${UUIDUtil.randomUUID}"

    def hasAnActiveConnection: Boolean = isConnected()
    private val connectionTimestamp = System.currentTimeMillis()
    def getConnectionTimestamp: Long = connectionTimestamp

    private[ClientInfo] val uniqueDirs = new ConcurrentHashMap[Project, AbsolutePath]()

    def hasManagedClassesDirectories: Boolean = bspClientClassesRootDir.nonEmpty

    /**
     * Selects the parent root directory where all client classes directories
     * will be created. The root classes directory can be derived from either
     * the project or a classes directory specified by the bsp client in its
     * initialization handshake. The semantics for the management of these
     * directories change depending on how the root dir for client classes
     * directories is derived.
     *
     * If bloop uses the parent of the generic classes directory as the root of
     * all client classes directories, then it also manages its contents and
     * can remove these classes directories as it sees fits (typically after
     * the client shuts down the connection). A managed directory is always
     * created inside a project-specific directory so internal directories can
     * use a format directory that already assumes the project id.
     *
     * Else, if the client passes its own root classes directory, then Bloop
     * only creates new directories but it doesn't remove them at all and
     * instead leaves the management of the contents of these directories to
     * the client. Bloop will still write compilation products in the client
     * directories when a client compile happens, but it's the responsibility
     * of the client to remove them from disk. An unmanaged directory is global
     * and it must contain classes directories for every project/build target
     * in such a way that there's no clash among them.
     */
    def parentForClientClassesDirectories(
        project: Project
    ): Option[Either[AbsolutePath, AbsolutePath]] = {
      if (ownsBuildFiles) None
      else {
        bspClientClassesRootDir match {
          case None => Some(Right(project.clientClassesRootDirectory))
          case Some(bspClientClassesRootDir) => Some(Left(bspClientClassesRootDir))
        }
      }
    }

    /**
     * Gets a unique classes directory to store classes and any kind of
     * compilation products. This classes directory can be freely accessed and
     * managed by the client without any further intervention except its
     * deletion when the client exits.
     */
    def getUniqueClassesDirFor(project: Project, forceGeneration: Boolean): AbsolutePath = {
      val classesDir = project.genericClassesDir.underlying
      parentForClientClassesDirectories(project) match {
        case None =>
          AbsolutePath(Files.createDirectories(project.genericClassesDir.underlying).toRealPath())
        case Some(parent) =>
          val classesDirName = classesDir.getFileName()
          uniqueDirs.computeIfAbsent(
            project,
            (project: Project) => {
              val newClientDir = parent match {
                case Left(unmanagedGlobalRootDir) =>
                  // Use format that avoids clashes between projects when storing in global root
                  val projectDirName = s"${this.name}-${project.name}-$classesDirName"
                  unmanagedGlobalRootDir.resolve(Filenames.escapeSpecialCharacters(projectDirName))
                case Right(managedProjectRootDir) =>
                  // We add unique id because we need it to correctly delete orphan dirs
                  val projectDirName = s"$classesDirName-$uniqueId"
                  managedProjectRootDir.resolve(projectDirName)
              }
              AbsolutePath(
                if (!forceGeneration) newClientDir.underlying
                else Files.createDirectories(newClientDir.underlying).toRealPath()
              )
            }
          )
      }
    }

    override def toString(): String =
      s"bsp client '$name $version' (since ${activeSinceMillis(connectionTimestamp)})"
  }

  val internalClassesNameFormat = "(.*)-[^-]*-[^-]*-[^-]*$".r

  /**
   * Returns the path from which we derived an internal compile classes directory.
   *
   * This method relies on two key invariants:
   *
   *   1. Internal compile classes directories reuse the external client classes
   *      directories name as a prefix. This logic is implemented in
   *      [[bloop.CompileOutPaths]].
   *   2. External client classes directories use the generic classes directory
   *      name (which we are trying to obtain here) as the suffix of their name and
   *      then they append a well-specified format of `$clientName-$randomId` (see
   *      `uniqueId` in `BspClientInfo`).
   *
   * So, in short, this function turns
   * `$genericClassesDirName-$clientName-$randomId-$internalId` into
   * `$genericClassesDirName`.
   *
   * @return The generic classes name of the project associated with the internal classes dir.
   */
  def toGenericClassesDir(internalClassesDir: AbsolutePath): Option[String] = {
    val internalDirName = internalClassesDir.underlying.getFileName().toString
    internalDirName match {
      case internalClassesNameFormat(genericClassesName) => Some(genericClassesName)
      case _ => None
    }
  }

  def activeSinceMillis(startMs: Long) = {
    import java.time.{Instant, Duration}
    val start = Instant.ofEpochMilli(startMs)
    val now = Instant.ofEpochMilli(System.currentTimeMillis())
    val duration = Duration.between(start, now)
    // https://stackoverflow.com/questions/3471397/how-can-i-pretty-print-a-duration-in-java
    duration.toString().substring(2).replaceAll("(\\d[HMS])(?!$)", "$1 ").toLowerCase
  }

  def deleteOrphanClientBspDirectories(
      currentBspClients: => Traversable[BspClientInfo],
      out: PrintStream,
      err: PrintStream
  ): Unit = {
    import scala.collection.JavaConverters._
    val deletionThresholdInstant = Instant.now().minusSeconds(45)
    val currentBspConnectedClients = currentBspClients
    val connectedBspClientIds = new mutable.ListBuffer[String]()
    val projectsToVisit = new mutable.HashMap[Project, BspClientInfo]()

    // Collect projects that were touched by the current bsp clients
    currentBspConnectedClients.foreach { client =>
      if (client.hasAnActiveConnection)
        connectedBspClientIds.+=(client.uniqueId)
      client.uniqueDirs.keySet.asScala.iterator.foreach { project =>
        projectsToVisit.+=(project -> client)
      }
    }

    projectsToVisit.foreach {
      case (project, client) =>
        client.parentForClientClassesDirectories(project) match {
          case None =>
            () // If owns build files, original generic classes dirs are used
          case Some(Left(unmanagedDir)) =>
            () // If unmanaged, it's managed by BSP client, do nothing

          case Some(Right(bspClientClassesDir)) =>
            try {
              Paths.list(bspClientClassesDir).foreach { clientDir =>
                val dirName = clientDir.underlying.getFileName().toString
                val attrs = Files.readAttributes(clientDir.underlying, classOf[BasicFileAttributes])
                val isOldDir = attrs.creationTime.toInstant.isBefore(deletionThresholdInstant)
                val isWhitelisted = CliClientInfo.isStableDirName(dirName) ||
                  connectedBspClientIds.exists(clientId => dirName.endsWith(s"-$clientId"))

                if (isWhitelisted && !isOldDir) ()
                else {
                  try {
                    out.println(s"Deleting orphan directory ${clientDir}")
                    bloop.io.Paths.delete(clientDir)
                  } catch {
                    case _: NoSuchFileException => ()
                    case NonFatal(t) =>
                      err.println(s"Failed to delete unused client directory $clientDir")
                      t.printStackTrace(err)
                  }
                }
              }
            } catch {
              // Catch errors so that we process the rest of projects
              case NonFatal(t) =>
                err.println(s"Couldn't prune orphan classes directory for ${project.name}")
                t.printStackTrace(err)
            }
        }
    }
  }
}
