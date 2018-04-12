package bloop.reporter

import bloop.io.AbsolutePath
import bloop.logging.BspLogger
import xsbti.Position

import scala.collection.mutable

final class BspReporter(
    override val logger: BspLogger,
    override val cwd: AbsolutePath,
    sourcePositionMapper: Position => Position,
    override val config: ReporterConfig,
    override val _problems: mutable.Buffer[Problem] = mutable.ArrayBuffer.empty
) extends Reporter(logger, cwd, sourcePositionMapper, config, _problems) {
  override def printSummary(): Unit = () // Not implemented in bsp.
  override protected def logFull(problem: Problem): Unit = logger.diagnostic(problem)
}
