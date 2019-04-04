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
      tracer: BraveTracer
  ): Task[Seq[FileHash]] = {
    // We'll add the file hashes to the indices here and return it at the end
    val classpathHashes = new Array[FileHash](classpath.length)
    case class AcquiredTask(file: File, idx: Int, p: Promise[FileHash])

    val parallelConsumer = {
      Consumer.foreachParallelTask[AcquiredTask](parallelUnits) {
        case AcquiredTask(file, idx, p) =>
          Task {
            import scala.util.control.NonFatal
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
              case NonFatal(t) => BloopStamps.emptyHash(file)
            }
            classpathHashes(idx) = hash
            p.success(hash)
            hashingPromises.remove(file)
            ()
          }
      }
    }

    tracer.traceTask("computing hashes") { tracer =>
      val acquiredByOtherTasks = new mutable.ListBuffer[Task[Unit]]()
      val acquiredByThisHashingProcess = new mutable.ListBuffer[AcquiredTask]()

      classpath.zipWithIndex.foreach {
        case t @ (absoluteEntry, idx) =>
          val entry = absoluteEntry.toFile
          val entryPromise = Promise[FileHash]()
          val promise = hashingPromises.putIfAbsent(entry, entryPromise)
          if (promise != null) { // The entry was acquired by other task
            acquiredByOtherTasks.+=(
              Task.fromFuture(promise.future).map { hash =>
                classpathHashes(idx) = hash
                ()
              }
            )
          } else { // The entry is acquired by this task
            acquiredByThisHashingProcess.+=(AcquiredTask(entry, idx, entryPromise))
          }
      }

      // Let's first obtain the hashes for those entries which we acquired
      Observable
        .fromIterable(acquiredByThisHashingProcess)
        .consumeWith(parallelConsumer)
        .flatMap { _ =>
          // Then, we block on the hashes
          val blockingBatches = {
            acquiredByOtherTasks.toList
              .grouped(parallelUnits)
              .map { group =>
                Task.gatherUnordered(group)
              }
          }

          Task.sequence(blockingBatches).map(_.flatten).map { _ =>
            classpathHashes
          }
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
