package bloop.io

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.control.NonFatal

import bloop.logging.Logger
import bloop.task.Task
import bloop.tracing.BraveTracer

import monix.execution.atomic.AtomicBoolean
import sbt.internal.inc.bloop.internal.BloopStamps
import sbt.io.IO
import xsbti.compile.FileHash
import bloop.logging.DebugFilter

private final case class JarMetadata(
    modifiedTime: FileTime,
    size: Long
)

object ClasspathHasher {

  private[this] val cacheMetadataJar = new ConcurrentHashMap[Path, (JarMetadata, FileHash)]()

  /**
   * Hash the classpath in parallel
   *
   * NOTE: When the task returned by this method is cancelled, the promise
   * `cancelCompilation` will be completed and the returned value will be
   * empty. The call-site needs to handle the case where cancellation happens.
   *
   * @param classpath The list of files to be hashed (if they exist).
   * @param parallelUnits The amount of classpath entries we can hash at once.
   * @param cancelCompilation A promise that will be completed if task is cancelled.
   * @param scheduler The scheduler that should be used for internal Monix usage.
   * @param logger The logger where every action will be logged.
   * @param tracer A tracer to keep track of timings in Zipkin.
   * @return A task returning an error if the task was cancelled or a complete list of hashes.
   */
  def hash(
      classpath: Array[AbsolutePath],
      parallelUnits: Int,
      cancelCompilation: Promise[Unit],
      logger: Logger,
      tracer: BraveTracer,
      serverOut: PrintStream
  ): Task[Either[Unit, Vector[FileHash]]] = {
    val timeoutSeconds: Long = 20L

    implicit val debugFilter: DebugFilter = DebugFilter.Compilation

    val isCancelled = AtomicBoolean(false)
    def makeHashingTask(path: Path): Task[FileHash] = {
      val isHashingComplete = AtomicBoolean(false)

      def hashFile(): FileHash = {
        val attrs = Files.readAttributes(path, classOf[BasicFileAttributes])
        if (attrs.isDirectory) BloopStamps.directoryHash(path)
        else {
          val currentMetadata =
            JarMetadata(FileTime.fromMillis(IO.getModifiedTimeOrZero(path.toFile)), attrs.size())
          val file = path.toAbsolutePath.toString
          Option(cacheMetadataJar.get(path)) match {
            case Some((metadata, hashHit)) if metadata == currentMetadata =>
              logger.debug(s"Using cached hash for $file")
              hashHit
            case other =>
              if (other.isDefined) {
                logger.debug(
                  s"Cached entry for $file has different metadata, hash will be recomputed"
                )
              }
              tracer.traceVerbose(s"computing hash $file") { _ =>
                val newHash =
                  FileHash.of(path, ByteHasher.hashFileContents(path.toFile))
                cacheMetadataJar.put(path, (currentMetadata, newHash))
                newHash
              }
          }
        }
      }

      def hash(path: Path): Task[FileHash] = Task {
        try {
          if (cancelCompilation.isCompleted) {
            BloopStamps.cancelledHash(path)
          } else if (isCancelled.get) {
            cancelCompilation.trySuccess(())
            BloopStamps.cancelledHash(path)
          } else {
            val hash = hashFile()
            isHashingComplete.set(true)
            hash
          }
        } catch {
          // Can happen when a file doesn't exist, for example
          case NonFatal(_) => BloopStamps.emptyHash(path)
        }
      }

      /*
       * As a protective measure, set up a timeout for hashing which complete hashing with empty value to unlock
       * downstream clients on the assumption that the hashing of this
       * entry is too slow because of something that happened to this
       * process. The completion of the downstream promise will also log a
       * warning to the downstream users so that they know that a hashing
       * process is unusually slow.
       */
      val timeoutFallback: Task[FileHash] = Task {
        val msg =
          s"Hashing ${path} is taking more than ${timeoutSeconds}s"
        try {
          pprint.log(msg)
          logger.warn(msg)
          serverOut.println(msg)
        } catch { case NonFatal(_) => () }
        BloopStamps.emptyHash(path)
      }

      hash(path).timeoutTo(
        duration = timeoutSeconds.seconds,
        backup = timeoutFallback
      )
    }

    val onCancel = Task(isCancelled.compareAndSet(false, true)).void

    val tasks = classpath.toVector.map(path => makeHashingTask(path.underlying))
    Task
      .parSequenceN(parallelUnits)(tasks)
      .map { result =>
        if (isCancelled.get || cancelCompilation.isCompleted) {
          cancelCompilation.trySuccess(())
          Left(())
        } else {
          Right(result.toVector)
        }
      }
      .doOnCancel(onCancel)
  }
}
