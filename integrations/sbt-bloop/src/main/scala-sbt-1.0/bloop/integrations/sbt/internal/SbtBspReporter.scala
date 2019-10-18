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

object SbtBspReporter {
  def report(diagnostic: Diagnostic, tid: TextDocumentIdentifier, reporter: Reporter): Unit = {
    val severity: Severity = {
      diagnostic.getSeverity match {
        case DiagnosticSeverity.ERROR => Severity.Error
        case DiagnosticSeverity.WARNING => Severity.Warn
        case DiagnosticSeverity.INFORMATION => Severity.Warn
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
      InterfaceUtil.position(
        line0 = Some(start.getLine),
        content = diagnostic.getCode(),
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
}
