package bloop.reporter
import java.io.File

import bloop.data.Project
import bloop.io.AbsolutePath
import bloop.logging.Logger
import xsbti.compile.CompileAnalysis
import xsbti.{Position, Severity}
import ch.epfl.scala.bsp
import sbt.internal.inc.Analysis

import scala.collection.mutable

final class LogReporter(
    override val logger: Logger,
    override val cwd: AbsolutePath,
    sourcePositionMapper: Position => Position,
    override val config: ReporterConfig,
    override val _problems: mutable.Buffer[Problem] = mutable.ArrayBuffer.empty
) extends Reporter(logger, cwd, sourcePositionMapper, config, _problems) {

  // Contains the files that are compiled in all incremental compiler cycles
  private val compilingFiles = mutable.HashSet[File]()

  private final val format = config.format(this)
  override def printSummary(): Unit = {
    if (config.reverseOrder) { _problems.reverse.foreach(logFull) }
    format.printSummary()
  }

  /**
   * Log the full error message for `problem`.
   *
   * @param problem The problem to log.
   */
  override protected def logFull(problem: Problem): Unit = {
    val text = format.formatProblem(problem)
    problem.severity match {
      case Severity.Error => logger.error(text)
      case Severity.Warn => logger.warn(text)
      case Severity.Info => logger.info(text)
    }
  }

  override def reportStartIncrementalCycle(sources: Seq[File], outputDirs: Seq[File]): Unit = {
    // TODO(jvican): Fix https://github.com/scalacenter/bloop/issues/386 here
    require(sources.size > 0) // This is an invariant enforced in the call-site
    compilingFiles ++= sources

    val (javaSources, scalaSources) = sources.partition(_.getName.endsWith(".java"))
    val scalaMsg = Analysis.counted("Scala source", "", "s", scalaSources.size)
    val javaMsg = Analysis.counted("Java source", "", "s", javaSources.size)
    val combined = scalaMsg ++ javaMsg
    val targets = outputDirs.map(_.getAbsolutePath).mkString(",")
    logger.info(combined.mkString("Compiling ", " and ", s" to $targets ..."))
  }

  override def reportEndIncrementalCycle(): Unit = {
    logger.info("Done compiling.")
  }

  override def reportStartCompilation(previousProblems: List[xsbti.Problem]): Unit = ()
  override def reportEndCompilation(
      previousAnalysis: Option[CompileAnalysis],
      analysis: Option[CompileAnalysis],
      code: bsp.StatusCode
  ): Unit = {
    def warningsFromPreviousRuns(previous: CompileAnalysis): List[xsbti.Problem] = {
      import scala.collection.JavaConverters._
      val previousSourceInfos = previous.readSourceInfos().getAllSourceInfos.asScala.toMap
      val eligibleSourceInfos =
        previousSourceInfos.filterKeys(f => !compilingFiles.contains(f)).values
      eligibleSourceInfos.flatMap { i =>
        i.getReportedProblems.filter(_.severity() == xsbti.Severity.Warn)
      }.toList
    }

    code match {
      case bsp.StatusCode.Ok =>
        // Report warnings that occurred in previous compilation cycles only if
        previousAnalysis.foreach { previous =>
          // Note that buffered warnings are not added back to the current analysis on purpose
          warningsFromPreviousRuns(previous).foreach(p => log(p))
        }
      case _ => ()
    }
  }
}

object LogReporter {
  def fromAnalysis(
      analysis: CompileAnalysis,
      cwd: AbsolutePath,
      logger: Logger
  ): Reporter = {
    import scala.collection.JavaConverters._
    val sourceInfos = analysis.readSourceInfos.getAllSourceInfos.asScala.toBuffer
    val ps = sourceInfos.flatMap(_._2.getReportedProblems).map(Problem.fromZincProblem(_))
    new LogReporter(logger, cwd, identity, ReporterConfig.defaultFormat, ps)
  }
}
