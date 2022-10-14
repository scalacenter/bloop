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

import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.task.Task
import bloop.tracing.BraveTracer

import monix.execution.atomic.AtomicBoolean
import sbt.internal.inc.bloop.internal.BloopStamps
import sbt.io.IO
import xsbti.compile.FileHash

class ClasspathHasher {
  import ClasspathHasher._

  private[this] val hashingPromises = new ConcurrentHashMap[Path, Promise[FileHash]]()
  private[this] val cacheMetadataJar = new ConcurrentHashMap[Path, (JarMetadata, FileHash)]()

  /**
   * Hash the classpath in parallel
   *
   * NOTE: When the task returned by this method is cancelled, the promise `cancelCompilation` will
   * be completed and the returned value will be empty. The call-site needs to handle the case where
   * cancellation happens.
   *
   * @param classpath
   *   The list of files to be hashed (if they exist).
   * @param parallelUnits
   *   The amount of classpath entries we can hash at once.
   * @param cancelCompilation
   *   A promise that will be completed if task is cancelled.
   * @param scheduler
   *   The scheduler that should be used for internal Monix usage.
   * @param logger
   *   The logger where every action will be logged.
   * @param tracer
   *   A tracer to keep track of timings in Zipkin.
   * @return
   *   A task returning an error if the task was cancelled or a complete list of hashes.
   */
  def hash(
      classpath: Array[AbsolutePath],
      parallelUnits: Int,
      cancelCompilation: Promise[Unit],
      logger: Logger,
      tracer: BraveTracer,
      serverOut: PrintStream
  ): Task[Either[Unit, Vector[FileHash]]] = {
    val timeoutSeconds: Long = 10L

    implicit val debugFilter: DebugFilter = DebugFilter.Compilation

    val isCancelled = AtomicBoolean(false)
    def makeHashingTask(path: Path, promise: Promise[FileHash]): Task[FileHash] = {

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
              } else {
                logger.debug(s"Cache miss for $file, hash will be computed")
              }
              tracer.traceVerbose(s"computing hash for $file") { _ =>
                val newHash =
                  FileHash.of(path, ByteHasher.hashFileContents(path.toFile))
                cacheMetadataJar.put(path, (currentMetadata, newHash))
                newHash
              }
          }
        }
      }

      def hash(path: Path): Task[FileHash] = Task {
        val hash =
          try {
            if (isCancelled.get) {
              cancelCompilation.trySuccess(())
              BloopStamps.cancelledHash(path)
            } else {
              hashFile()
            }
          } catch {
            // Can happen when a file doesn't exist, for example
            case NonFatal(_) => BloopStamps.emptyHash(path)
          }
        hashingPromises.remove(path, promise)
        promise.trySuccess(hash)
        hash
      }

      /*
       * As a protective measure, set up a timeout for hashing which complete hashing with empty value to unlock
       * downstream clients on the assumption that the hashing of this
       * entry is too slow because of something that happened to this
       * process. The completion of the downstream promise will also log a
       * warning to the downstream users so that they know that a hashing
       * process is unusually slow.
       *
       * There is no need for specyfying `doOnCancel` callback, this task won't start before timeout (see timeoutTo implementation)
       */
      val timeoutFallback: Task[FileHash] = Task {
        val cancelledHash = BloopStamps.emptyHash(path)
        val msg =
          s"Hashing ${path} is taking more than ${timeoutSeconds}s"
        try {
          hashingPromises.remove(path, promise)
          promise.trySuccess(cancelledHash)
          logger.warn(msg)
          serverOut.println(msg)
        } catch { case NonFatal(_) => () }
        cancelledHash
      }

      hash(path).timeoutTo(
        duration = timeoutSeconds.seconds,
        backup = timeoutFallback
      )
    }

    def acquireHashingEntry(entry: Path): FileHashingResult = {
      if (isCancelled.get) Computed(Task.now(FileHash.of(entry, BloopStamps.cancelledHash)))
      else {
        val entryPromise = Promise[FileHash]()
        val promise = Option(hashingPromises.putIfAbsent(entry, entryPromise))

        promise match {
          // The hashing is done by this process, compute hash and complete the promise
          case None =>
            Computed(makeHashingTask(entry, entryPromise))
          // The hashing is acquired by another process, wait on its result
          case Some(promise) =>
            logger.debug(s"Wait for hashing of $entry to complete")
            FromPromise(waitForAnotherTask(entry, promise))
        }
      }
    }

    def waitForAnotherTask(entry: Path, promise: Promise[FileHash]): Task[FileHash] =
      Task.fromFuture(promise.future).flatMap { hash =>
        hash.hash() match {
          case BloopStamps.cancelledHash =>
            if (cancelCompilation.isCompleted)
              Task.now(FileHash.of(entry, BloopStamps.cancelledHash))
            else {
              // If the process that acquired it cancels the computation, try acquiring it again
              logger
                .warn(s"Unexpected hash computation of $entry was cancelled, restarting...")
              acquireHashingEntry(entry).task
            }
          case _ =>
            Task.now(hash)
        }
      }

    val onCancel = Task(isCancelled.compareAndSet(false, true)).unit

    val (computing, fromAnotherTask) =
      classpath.toVector.map(path => acquireHashingEntry(path.underlying)).partition {
        case _: Computed => true
        case _: FromPromise => false
      }

    // first compute all the entries that are computed by this run
    // then start waiting for entries that are computed by another tasks
    val hashes = for {
      computed <- Task.parSequenceN(parallelUnits)(computing.map(_.task).toList)
      fromPromises <- Task.parSequenceN(parallelUnits)(fromAnotherTask.map(_.task).toList)
    } yield computed ++ fromPromises

    hashes
      .map { result =>
        pprint.log(this.cacheMetadataJar.size())
        pprint.log(this.hashingPromises.size())
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

object ClasspathHasher {
  final val global: ClasspathHasher = new ClasspathHasher

  private final case class JarMetadata(
      modifiedTime: FileTime,
      size: Long
  )

  /**
   * File hash can be computed in 2 ways:
   * 1) `Computed` - The file content is going to be hashed
   * 2) `FromPromise` - The hash result is going to be obtained from the promise (another running
   *     session of hashing is computing it)
   *
   * It's important to distinguish between them, because `Computed` case should be treated will
   * higher priority as it needs CPU and `FromPromise` is just waiting on the result.
   */
  private sealed trait FileHashingResult {
    def task: Task[FileHash]
  }
  private final case class Computed(task: Task[FileHash]) extends FileHashingResult
  private final case class FromPromise(task: Task[FileHash]) extends FileHashingResult
}
