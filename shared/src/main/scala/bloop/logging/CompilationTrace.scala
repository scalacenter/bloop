package bloop.logging

import java.time.Instant

import bloop.io.AbsolutePath

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

/**
 * Defines the compilation trace information that can be serialized to JSON.
 * This provides detailed information about compilation activities for debugging
 * transient compilation failures.
 */
case class CompilationTrace(
    entries: List[CompilationTraceEntry]
)

case class CompilationTraceEntry(
    project: String,
    startTime: Instant,
    endTime: Option[Instant],
    compiledFiles: List[String],
    diagnostics: List[CompilationDiagnostic],
    artifacts: List[CompilationArtifact],
    isNoOp: Boolean,
    result: String // "Success", "Failed", "Cancelled", etc.
)

case class CompilationDiagnostic(
    file: String,
    severity: String, // "error", "warning", "info"
    message: String,
    line: Option[Int],
    column: Option[Int]
)

case class CompilationArtifact(
    source: String,
    destination: String,
    artifactType: String // "class", "resource", "analysis", etc.
)

object CompilationTrace {
  implicit val codecDiagnostic: JsonValueCodec[CompilationDiagnostic] = 
    JsonCodecMaker.make[CompilationDiagnostic]
  implicit val codecArtifact: JsonValueCodec[CompilationArtifact] = 
    JsonCodecMaker.make[CompilationArtifact]
  implicit val codecEntry: JsonValueCodec[CompilationTraceEntry] = 
    JsonCodecMaker.make[CompilationTraceEntry]
  implicit val codec: JsonValueCodec[CompilationTrace] = 
    JsonCodecMaker.make[CompilationTrace]
}