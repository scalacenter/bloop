package bloop.logging

import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import bloop.io.AbsolutePath
import bloop.reporter.Problem

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.WriterConfig
import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray

/**
 * Records compilation trace information and writes it to a JSON file.
 * This is useful for debugging transient compilation failures.
 */
class CompilationTraceRecorder(private val traceFile: AbsolutePath) {
  private val entries = new ConcurrentHashMap[String, CompilationTraceEntry]()
  
  def startCompilation(project: String): Unit = {
    try {
      val entry = CompilationTraceEntry(
        project = project,
        startTime = Instant.now(),
        endTime = None,
        compiledFiles = List.empty,
        diagnostics = List.empty,
        artifacts = List.empty,
        isNoOp = false,
        result = "In Progress"
      )
      entries.put(project, entry)
    } catch {
      case ex: Exception =>
        // Don't fail compilation due to trace recording errors
        System.err.println(s"Failed to start compilation trace for project $project: ${ex.getMessage}")
    }
  }
  
  def endCompilation(
      project: String,
      compiledFiles: List[String],
      diagnostics: List[Problem],
      artifacts: List[CompilationArtifact],
      isNoOp: Boolean,
      result: String
  ): Unit = {
    try {
      val currentEntry = entries.get(project)
      if (currentEntry != null) {
        val traceProblems = diagnostics.map { problem =>
          val pos = problem.position
          CompilationDiagnostic(
            file = pos.sourceFile.map(_.toString).getOrElse("unknown"),
            severity = problem.severity.toString.toLowerCase,
            message = problem.message,
            line = pos.line,
            column = pos.column
          )
        }
        
        val updatedEntry = currentEntry.copy(
          endTime = Some(Instant.now()),
          compiledFiles = compiledFiles,
          diagnostics = traceProblems,
          artifacts = artifacts,
          isNoOp = isNoOp,
          result = result
        )
        entries.put(project, updatedEntry)
      }
    } catch {
      case ex: Exception =>
        // Don't fail compilation due to trace recording errors
        System.err.println(s"Failed to end compilation trace for project $project: ${ex.getMessage}")
    }
  }
  
  def recordArtifactCopy(project: String, source: String, destination: String, artifactType: String): Unit = {
    try {
      val currentEntry = entries.get(project)
      if (currentEntry != null) {
        val artifact = CompilationArtifact(source, destination, artifactType)
        val updatedEntry = currentEntry.copy(artifacts = currentEntry.artifacts :+ artifact)
        entries.put(project, updatedEntry)
      }
    } catch {
      case ex: Exception =>
        // Don't fail compilation due to trace recording errors
        System.err.println(s"Failed to record artifact copy for project $project: ${ex.getMessage}")
    }
  }
  
  def writeTrace(): Unit = {
    try {
      val trace = CompilationTrace(entries.values().asScala.toList)
      val bytes = writeToArray(trace, WriterConfig.withIndentionStep(2))
      Files.createDirectories(traceFile.getParent.underlying)
      Files.write(traceFile.underlying, bytes)
    } catch {
      case ex: Exception =>
        // Don't fail compilation due to trace writing errors
        // Just log the error and continue
        System.err.println(s"Failed to write compilation trace to ${traceFile}: ${ex.getMessage}")
    }
  }
}

object CompilationTraceRecorder {
  def create(configDir: AbsolutePath): CompilationTraceRecorder = {
    val traceFile = configDir.resolve("compilation.trace.json")
    new CompilationTraceRecorder(traceFile)
  }
}