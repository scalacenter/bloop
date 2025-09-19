package bloop

import java.io.File
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters._

import bloop.io.AbsolutePath
import bloop.task.Task

import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import xsbti.VirtualFileRef
import xsbti.compile.CompileAnalysis
import xsbti.compile.analysis.Stamp
import bloop.util.HashedSource

/**
 * Each time a new compile analysis is produced for a given client, it is given to
 * the [[ClientClassObserver]] which computes the list of classes that changed or got created.
 *
 * A client can subscribe to the observer to get notified of classes to update.
 * It is used by DAP to hot reload classes in the debuggee process.
 *
 * @param clientClassesDir the class directory for the client
 */
private[bloop] class ClientClassesObserver(val classesDir: AbsolutePath) {
  private val previousAnalysis: AtomicReference[CompileAnalysis] = new AtomicReference()
  private val classesSubject: PublishSubject[Seq[String]] = PublishSubject()

  def observable: Observable[Seq[String]] = classesSubject

  def nextAnalysis(analysis: CompileAnalysis): Task[Unit] = {
    val prev = previousAnalysis.getAndSet(analysis)
    if (prev != null && classesSubject.size > 0) {
      Task {
        val previousStamps = prev.readStamps.getAllProductStamps
        analysis.readStamps.getAllProductStamps.asScala.iterator.collect {
          case (vf, stamp) if isClassFile(vf) && isNewer(stamp, previousStamps.get(vf)) =>
            getFullyQualifiedClassName(vf)
        }.toSeq
      }
        .flatMap { classesToUpdate =>
          Task.fromFuture(classesSubject.onNext(classesToUpdate)).map(_ => ())
        }
    } else Task.unit
  }

  private def isClassFile(vf: VirtualFileRef): Boolean = vf.id.endsWith(".class")

  private def isNewer(current: Stamp, previous: Stamp): Boolean =
    previous == null || {
      val currentHash = current.getHash
      val previousHash = previous.getHash
      currentHash.isPresent &&
      (!previousHash.isPresent || currentHash.get != previousHash.get)
    }

  private def getFullyQualifiedClassName(vf: VirtualFileRef): String = {
    val path = HashedSource.converter.toPath(vf)
    val relativePath = classesDir.underlying.relativize(path)
    relativePath.toString.replace(File.separator, ".").stripSuffix(".class")
  }
}
