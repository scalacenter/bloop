package bloop

import java.io.File
import java.nio.file.Path

import monix.reactive.{Observer, Observable, MulticastStrategy}
import monix.execution.Scheduler

/**
 * Defines the places where compilation writes compilation products to.
 * These directories are "managed", which means they are internal to bloop
 * and never escape the scope of the compilation task (e.g. they can only
 * be used for compilation purposes).
 *
 * @param sharedDeleteOnlyClassesOut The classes directory that
 *        contains compilation products from previous compilations, if any.
 *        The class files in this directory might only be deleted for the
 *        purpose of incremental compiler correctness. The deletion can
 *        be done by any (concurrent) compilation process.
 *
 * @param uniqueWriteOnlyClassesOut The classes directory to which
 *        the compiler writes class files to. Any class file that may have
 *        been deleted in the [[sharedDeleteOnlyClassesOut]] by another
 *        process and not the compilation associated to this output will
 *        also be present in this directory.
 *
 * @param sharedBackupClassesOut The classes directory where we move
 *        all class files that have been deleted in the
 *        [[sharedDeleteOnlyClassesOut]] when Zinc invalidates symbols.
 */
case class CompileManagedOutput private (
    sharedDeleteOnlyClassesOut: Path,
    uniqueWriteOnlyClassesOut: Path,
    sharedBackupClassesOut: Path,
    deletedClassesAndResourcesObserver: Observer[File],
    deletedClassesAndResourcesObservable: Observable[File]
)

object CompileManagedOutput {
  def apply(
      sharedDeleteOnlyClassesOut: Path,
      uniqueWriteOnlyClassesOut: Path,
      sharedBackupClassesOut: Path,
      scheduler: Scheduler
  ): CompileManagedOutput = {
    val (observer, observable) = {
      Observable.multicast[File](
        MulticastStrategy.replay
      )(scheduler)
    }

    CompileManagedOutput(
      sharedDeleteOnlyClassesOut,
      uniqueWriteOnlyClassesOut,
      sharedBackupClassesOut,
      observer,
      observable
    )
  }
}
