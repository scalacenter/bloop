package bloop.data

import bloop.io.AbsolutePath
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
}

object ClientInfo {
  final case class CliClientInfo(
      id: String,
      private val isConnected: () => Boolean
  ) extends ClientInfo {
    def hasAnActiveConnection: Boolean = isConnected()
    private val connectionTimestamp = System.currentTimeMillis()
    def hasManagedClassesDirectories: Boolean = false
    def getConnectionTimestamp: Long = connectionTimestamp

    def getUniqueClassesDirFor(project: Project, forceGeneration: Boolean): AbsolutePath = {
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
      bspVersion: String,
      bspClientClassesRootDir: Option[AbsolutePath],
      private val isConnected: () => Boolean
  ) extends ClientInfo {
    // The format of this unique id is used in `toGenericClassesDir`
    val uniqueId: String = s"${this.name}-${UUIDUtil.randomUUID}"

    def hasAnActiveConnection: Boolean = isConnected()
    private val connectionTimestamp = System.currentTimeMillis()
    def getConnectionTimestamp: Long = connectionTimestamp

    import java.util.concurrent.ConcurrentHashMap
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
    ): Either[AbsolutePath, AbsolutePath] = {
      bspClientClassesRootDir match {
        case None => Right(project.bspClientClassesRootDirectory)
        case Some(bspClientClassesRootDir) => Left(bspClientClassesRootDir)
      }
    }

    /**
     * Gets a unique classes directory to store classes and any kind of
     * compilation products. This classes directory can be freely accessed and
     * managed by the client without any further intervention except its
     * deletion when the client exits.
     */
    def getUniqueClassesDirFor(project: Project, forceGeneration: Boolean): AbsolutePath = {
      uniqueDirs.computeIfAbsent(
        project,
        (project: Project) => {
          val classesDirName = project.genericClassesDir.underlying.getFileName()
          val newClientDir = parentForClientClassesDirectories(project) match {
            case Left(unmanagedGlobalRootDir) =>
              // Use format that avoids clashes between projects when storing in global root
              val projectDirName = s"${this.name}-${project.name}-$classesDirName"
              unmanagedGlobalRootDir.resolve(projectDirName)
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
      currentBspClients: () => Traversable[BspClientInfo],
      logger: Logger,
      currentAttempts: Int = 0
  ): Unit = {
    if (currentAttempts >= 5) {
      // Give up cleanup temporarily if clients map is constantly changing and cannot be done safely
      ()
    } else {
      import scala.collection.JavaConverters._
      val initialBspConnectedClients = currentBspClients()
      val connectedBspClientIds = new mutable.ListBuffer[String]()
      val projectsToVisit = new mutable.HashMap[Project, BspClientInfo]()
      initialBspConnectedClients.foreach { client =>
        if (client.hasAnActiveConnection)
          connectedBspClientIds.+=(client.uniqueId)
        client.uniqueDirs.keySet.asScala.iterator.foreach { project =>
          projectsToVisit.+=(project -> client)
        }
      }

      /*
       * The deletion of orphan client directories is implemented with the
       * following constraints:
       *
       *  1. It can be run in parallel by any new client connection.
       *  2. It must not delete directories owned by active clients under any
       *     circumstances. This includes protecting us from race conditions, which
       *     can happen in the following scenarios:
       *       * New client directories for a project are created in the file system
       *         but they are not yet added to `client.uniqueDirs`. We fix this
       *         condition by filtering the existing paths based on the unique id of
       *         the client info rather than using the in-memory paths contained in
       *         `uniqueDirs`.
       *       * A new client connects right after we obtain current clients and
       *         starts creating new client directories in projects, which would be
       *         incorrectly removed by our logic if we didn't check the clients we
       *         obtained initially and those that exist after listing all client
       *         directories is the same. When it's not the same, we retry the task
       *         again up to a limit of 5 times. If the fifth attempt doesn't work, we
       *         give up and let the cleanup for a moment where there is less
       *         connection activity in the server.
       *
       * We also protect ourselves from typical IO exceptions in case the file
       * system doesn't allow the bloop server to run operations on these
       * directories. This could happen if for example these directories are
       * owned by another user than the one running the bloop server.
       */
      projectsToVisit.foreach { kv =>
        val (project, client) = kv
        client.parentForClientClassesDirectories(project) match {
          case Left(unmanagedDir) => () // If unmanaged, it's managed by BSP client, do nothing
          case Right(bspClientClassesDir) =>
            try {
              val currentBspConnectedClients = currentBspClients()
              if (currentBspConnectedClients != initialBspConnectedClients) {
                deleteOrphanClientBspDirectories(
                  currentBspClients,
                  logger,
                  currentAttempts = currentAttempts + 1
                )
              } else {
                Paths.list(bspClientClassesDir).foreach { existingDir =>
                  val dirName = existingDir.underlying.getFileName().toString
                  // Whitelist those that are owned by clients that are active in the server
                  val isWhitelisted =
                    connectedBspClientIds.exists(clientId => dirName.endsWith(s"-$clientId"))
                  if (isWhitelisted) ()
                  else {
                    try {
                      logger.debug(s"Deleting orphan directory ${existingDir}")(DebugFilter.All)
                      bloop.io.Paths.delete(existingDir)
                    } catch {
                      case _: NoSuchFileException => ()
                      case NonFatal(t) =>
                        logger.debug(
                          s"Unexpected error when deleting unused client directory $existingDir"
                        )(DebugFilter.Bsp)
                        logger.trace(t)
                    }
                  }
                }
              }
            } catch {
              // Catch errors so that we process the rest of projects
              case NonFatal(t) =>
                logger.debug(
                  s"Unexpected error when processing unused client directories for ${project.name}"
                )(DebugFilter.Bsp)
                logger.trace(t)
            }
        }
      }
    }
  }
}
