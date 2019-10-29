package bloop.integrations.sbt.internal

import xsbti.Reporter

import ch.epfl.scala.bsp4j.Diagnostic
import xsbti.Position
import xsbti.Problem
import ch.epfl.scala.bsp4j.DiagnosticSeverity
import xsbti.Severity
import sbt.util.InterfaceUtil
import ch.epfl.scala.bsp4j.TextDocumentIdentifier
import java.net.URI
import java.nio.file.Paths
import java.io.File
import java.{util => ju}

object SbtBspReporter {
  def report(diagnostic: Diagnostic, tid: TextDocumentIdentifier, reporter: Reporter): Unit = {
    val severity: Severity = {
      diagnostic.getSeverity match {
        case DiagnosticSeverity.ERROR => Severity.Error
        case DiagnosticSeverity.WARNING => Severity.Warn
        case DiagnosticSeverity.INFORMATION => Severity.Info
        case DiagnosticSeverity.HINT => Severity.Info
      }
    }

    val sourceFile = Paths.get(URI.create(tid.getUri())).toFile
    val position = {
      val range = diagnostic.getRange()
      val start = range.getStart()
      val end = range.getEnd()
      val hasNoPosition =
        start.getLine() == 0 && start.getCharacter() == 0 &&
          end.getLine() == 0 && end.getCharacter() == 0
      new PositionImpl(
        line0 = Some(start.getLine),
        lineContent0 = diagnostic.getCode(),
        offset0 = None,
        pointer0 = None,
        pointerSpace0 = None,
        sourcePath0 = Some(sourceFile.getAbsolutePath),
        sourceFile0 = Some(sourceFile),
        startOffset0 = None,
        endOffset0 = None,
        startLine0 = None,
        startColumn0 = None,
        endLine0 = None,
        endColumn0 = None
      )
    }

    val msg = diagnostic.getMessage()
    reporter.log(InterfaceUtil.problem("", position, msg, severity, None))
  }

  final class PositionImpl(
      sourcePath0: Option[String],
      sourceFile0: Option[File],
      line0: Option[Int],
      lineContent0: String,
      offset0: Option[Int],
      pointer0: Option[Int],
      pointerSpace0: Option[String],
      startOffset0: Option[Int],
      endOffset0: Option[Int],
      startLine0: Option[Int],
      startColumn0: Option[Int],
      endLine0: Option[Int],
      endColumn0: Option[Int]
  ) extends xsbti.Position {
    val line = o2oi(line0)
    val lineContent = lineContent0
    val offset = o2oi(offset0)
    val sourcePath = InterfaceUtil.o2jo(sourcePath0)
    val sourceFile = InterfaceUtil.o2jo(sourceFile0)
    val pointer = o2oi(pointer0)
    val pointerSpace = InterfaceUtil.o2jo(pointerSpace0)
    override val startOffset = o2oi(startOffset0)
    override val endOffset = o2oi(endOffset0)
    override val startLine = o2oi(startLine0)
    override val startColumn = o2oi(startColumn0)
    override val endLine = o2oi(endLine0)
    override val endColumn = o2oi(endColumn0)
    override def toString =
      (sourcePath0, line0) match {
        case (Some(s), Some(l)) => s + ":" + l
        case (Some(s), _) => s + ":"
        case _ => ""
      }
  }

  import java.lang.{Integer => JInteger}
  private def o2oi(opt: Option[Int]): ju.Optional[JInteger] = {
    opt match {
      case Some(s) => ju.Optional.ofNullable[JInteger](s: JInteger)
      case None => ju.Optional.empty[JInteger]
    }
  }
}
