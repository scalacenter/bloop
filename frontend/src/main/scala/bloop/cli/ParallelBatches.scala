package bloop.cli

final case class ParallelBatches(number: Int)
object ParallelBatches {
  val Default: ParallelBatches = ParallelBatches(Runtime.getRuntime.availableProcessors())
}
