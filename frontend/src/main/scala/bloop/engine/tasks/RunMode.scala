package bloop.engine.tasks

sealed trait RunMode
object RunMode {
  final case object Normal extends RunMode

  /**
   * Run the JVM with a JDWP debug agent attached.
   *
   * @param address The fixed port to listen on, or `None` to let the JVM pick a free port
   *                (the chosen port is then reported on stdout).
   * @param suspend Whether the JVM should wait for a debugger to attach before running.
   */
  final case class Debug(address: Option[Int] = None, suspend: Boolean = true) extends RunMode
}
