package bloop.io

import bloop.logging.Logger
import bloop.logging.DebugFilter
import bloop.tracing.BraveTracer

import scala.collection.mutable
import scala.concurrent.Promise

import java.io.{File, InputStream}
import java.nio.file.{Files, NoSuchFileException, Path}
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import java.util.zip.ZipEntry

import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Observable, MulticastStrategy, Consumer}

import xsbti.compile.FileHash
import sbt.internal.inc.{EmptyStamp, Stamper}
import sbt.internal.inc.bloop.internal.BloopStamps
import sbt.io.IO
import java.util.concurrent.TimeUnit
import monix.eval.Callback

object ClasspathHasher {
  // For more safety, store both the time and size
  private type JarMetadata = (FileTime, Long)
  private[this] val hashingPromises = new ConcurrentHashMap[File, Promise[FileHash]]()
  private[this] val cacheMetadataJar = new ConcurrentHashMap[File, (JarMetadata, FileHash)]()

  /**
   * Hash the classpath in parallel with Monix's task.
   *
   * The hashing works in two steps: first, we try to acquire the hash of a given entry.
   * This "negotiation" step is required because we may be hashing other project's classpath
   * concurrently and we want to minimize stalling and make as much progress as we can hashing.
   * Those entries whose hashing couldn't be "acquired" are left to the second step, which blocks
   * until the ongoing hashing finishes.
   *
   * This approach allows us to control how many concurrent tasks we spawn to
   * new threads (and, therefore, how many threads we create in the io pool) and, at the same time,
   * allows us to do as much progress without blocking.
   *
   * @param classpath The list of files to be hashed (if they exist).
   * @param parallelUnits The amount of parallel hashing we can do.
   * @param tracer A tracer to keep track of timings in Zipkin.
   * @return A task returning a list of hashes.
   */
  def hash(
      classpath: Array[AbsolutePath],
      parallelUnits: Int,
      scheduler: Scheduler,
      logger: Logger,
      tracer: BraveTracer
  ): Task[Seq[FileHash]] = {
    val timeoutSeconds: Long = 15L
    // We'll add the file hashes to the indices here and return it at the end
    val classpathHashes = new Array[FileHash](classpath.length)
    case class AcquiredTask(file: File, idx: Int, p: Promise[FileHash])

    val parallelConsumer = {
      Consumer.foreachParallelAsync[AcquiredTask](parallelUnits) {
        case AcquiredTask(file, idx, p) =>
          // Use task.now because Monix's load balancer already forces an async boundary
          val hashingTask = Task.now {
            val hash = try {
              val filePath = file.toPath
              val attrs = Files.readAttributes(filePath, classOf[BasicFileAttributes])
              if (attrs.isDirectory) BloopStamps.directoryHash(file)
              else {
                val currentMetadata =
                  (FileTime.fromMillis(IO.getModifiedTimeOrZero(file)), attrs.size())
                Option(cacheMetadataJar.get(file)) match {
                  case Some((metadata, hashHit)) if metadata == currentMetadata => hashHit
                  case _ =>
                    tracer.trace(s"computing hash ${filePath.toAbsolutePath.toString}") { _ =>
                      val newHash = FileHash.of(file, ByteHasher.hashFileContents(file))
                      cacheMetadataJar.put(file, (currentMetadata, newHash))
                      newHash
                    }
                }
              }
            } catch {
              // Can happen when a file doesn't exist, for example
              case monix.execution.misc.NonFatal(t) => BloopStamps.emptyHash(file)
            }
            classpathHashes(idx) = hash
            hashingPromises.remove(file, p)
            p.trySuccess(hash)
            ()
          }

          /* 
           * As a protective measure, set up a task that will be run after 15s
           * of hashing and will complete the downstream promise to unlock
           * downstream clients on the assumption that the hashing of this
           * entry is too slow because of something that happened to this
           * process. The completion of the downstream promise will also log a
           * warning to the downstream users so that they know that a hashing
           * process is unusally slow.
           */
          val timeoutCancellation = scheduler.scheduleOnce(
            timeoutSeconds,
            TimeUnit.SECONDS,
            new Runnable {
              def run(): Unit = {
                val hash = BloopStamps.cancelledHash(file)
                // Complete if hashing for this entry hasn't finished in 15s, otherwise ignore
                hashingPromises.remove(file, p)
                if (p.trySuccess(hash)) {
                  logger.warn(
                    s"Hashing ${file} is taking more than ${timeoutSeconds}s, detaching downstream clients to unblock them..."
                  )
                }
                ()
              }
            }
          )

          hashingTask
            .doOnCancel(Task(timeoutCancellation.cancel()))
            .doOnFinish(_ => Task(timeoutCancellation.cancel()))
      }
    }

    tracer.traceTask("computing hashes") { tracer =>
      val acquiredByOtherTasks = new mutable.ListBuffer[Task[Unit]]()
      val acquiredByThisHashingProcess = new mutable.ListBuffer[AcquiredTask]()

      def acquireHashingEntry(entry: File, entryIdx: Int): Unit = {
        val entryPromise = Promise[FileHash]()
        val promise = hashingPromises.putIfAbsent(entry, entryPromise)
        if (promise == null) { // The hashing is done by this process
          acquiredByThisHashingProcess.+=(AcquiredTask(entry, entryIdx, entryPromise))
        } else { // The hashing is acquired by another process, wait on its result
          acquiredByOtherTasks.+=(
            Task.fromFuture(promise.future).flatMap { hash =>
              if (hash == BloopStamps.cancelledHash) {
                logger.warn(s"Disabling sharing of hash for $entry, upstream hashing took more than ${timeoutSeconds}s!")
                // If the process that acquired it cancels the computation, try acquiring it again
                Task.fork(Task.eval(acquireHashingEntry(entry, entryIdx)))
              } else {
                Task.now {
                  // Save the result hash in its index
                  classpathHashes(entryIdx) = hash
                  ()
                }
              }
            }
          )
        }
      }

      classpath.zipWithIndex.foreach {
        case t @ (absoluteEntry, idx) =>
          val entry = absoluteEntry.toFile
          acquireHashingEntry(entry, idx)
      }

      // Let's first obtain the hashes for those entries which we acquired
      Observable
        .fromIterable(acquiredByThisHashingProcess)
        .consumeWith(parallelConsumer)
        .flatMap { _ =>
          // Then, we block on the hashes sequentially to avoid creating too many blocking threads
          Task.sequence(acquiredByOtherTasks.toList).map(_ => classpathHashes)
        }
    }
  }

  private[this] val definedMacrosJarCache = new ConcurrentHashMap[File, (JarMetadata, Boolean)]()

  val blackboxReference = "scala/reflect/macros/blackbox/Context".getBytes
  val whiteboxReference = "scala/reflect/macros/whitebox/Context".getBytes
  def containsMacroDefinition(classpath: Seq[File]): Task[Seq[(File, Boolean)]] = {
    import org.zeroturnaround.zip.commons.IOUtils
    import org.zeroturnaround.zip.{ZipEntryCallback, ZipUtil}
    def readJar(jar: File): Task[(File, Boolean)] = Task {
      if (!jar.exists()) sys.error(s"File ${jar} doesn't exist")
      else {
        def detectMacro(jar: File): Boolean = {
          var found: Boolean = false
          ZipUtil.iterate(
            jar,
            new ZipEntryCallback {
              override def process(in: InputStream, zipEntry: ZipEntry): Unit = {
                if (found) ()
                else if (zipEntry.isDirectory) ()
                else if (!zipEntry.getName.endsWith(".class")) ()
                else {
                  try {
                    val bytes = IOUtils.toByteArray(in)
                    found = {
                      bytes.containsSlice(blackboxReference) ||
                      bytes.containsSlice(whiteboxReference)
                    }
                  } catch {
                    case t: Throwable => println(s"Error in ${t}")
                  }
                }
              }
            }
          )
          found
        }

        val attrs = Files.readAttributes(jar.toPath, classOf[BasicFileAttributes])
        val currentMetadata = (FileTime.fromMillis(IO.getModifiedTimeOrZero(jar)), attrs.size())

        Option(definedMacrosJarCache.get(jar)) match {
          case Some((metadata, hit)) if metadata == currentMetadata => jar -> hit
          case _ =>
            val detected = detectMacro(jar)
            definedMacrosJarCache.put(jar, (currentMetadata, detected))
            jar -> detected
        }
      }
    }

    Task.gatherUnordered(classpath.map(readJar(_)))
  }
}
