package bloop.cli

/** The configuration of the optimizer, if any. */
sealed trait OptimizerConfig

object OptimizerConfig {

  /**
   * Runs the optimizer in `debug` mode.
   * Optimization step is faster, but the produced code is slower.
   */
  case object Debug extends OptimizerConfig

  /**
   * Runs the optimizer in `release` mode.
   * Optimization step is slower, but the produced code is faster.
   */
  case object Release extends OptimizerConfig
}
