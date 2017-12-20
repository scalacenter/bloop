package bloop.exec

import java.util.UUID

/**
 * A `Thread` that can be identified via `id`. Its `id` is stored in an `InheritableThreadLocal`
 * variable that can be used to perform operations specific to that thread (e.g. selecting the
 * right outputstreams to use to simulate dedicated stdout / stderr.
 */
abstract class IdentifiedThread private (val id: UUID) extends Thread {
  def this() = this(UUID.randomUUID)
  final override def run(): Unit = {
    IdentifiedThread.id.set(id)
    work()
  }

  /** The main loop of this `Thread`. */
  def work(): Unit
}

object IdentifiedThread {

  /** An `InheritedThreadLocal` that can be used to identify the currently running `Thread`. */
  val id = new InheritableThreadLocal[UUID]
}
