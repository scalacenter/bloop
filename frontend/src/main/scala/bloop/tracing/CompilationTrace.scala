package bloop.tracing

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

case class CompilationTrace(
    project: String,
    files: Seq[String],
    diagnostics: Seq[TraceDiagnostic],
    artifacts: TraceArtifacts,
    isNoOp: Boolean,
    durationMs: Long
)

object CompilationTrace {
  implicit val codec: JsonValueCodec[CompilationTrace] =
    JsonCodecMaker.make[CompilationTrace]
  implicit val listCodec: JsonValueCodec[List[CompilationTrace]] =
    JsonCodecMaker.make[List[CompilationTrace]]
}

case class TraceDiagnostic(
    severity: String,
    message: String,
    range: Option[TraceRange],
    code: Option[String],
    source: Option[String]
)

case class TraceRange(
    startLine: Int,
    startCharacter: Int,
    endLine: Int,
    endCharacter: Int
)

case class TraceArtifacts(
    classesDir: String,
    analysisFile: String
)
