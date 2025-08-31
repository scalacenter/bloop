package bloop.bsp

import ch.epfl.scala.bsp.BuildTargetIdentifier
import ch.epfl.scala.bsp.Uri

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import jsonrpc4s.Endpoint

object BloopBspDefinitions {
  final case class BloopExtraBuildParams(
      ownsBuildFiles: Option[Boolean],
      clientClassesRootDir: Option[Uri],
      semanticdbVersion: Option[String],
      supportedScalaVersions: Option[List[String]],
      javaSemanticdbVersion: Option[String],
      enableBestEffortMode: Option[Boolean]
  )

  object BloopExtraBuildParams {
    val empty: BloopExtraBuildParams = BloopExtraBuildParams(
      ownsBuildFiles = None,
      clientClassesRootDir = None,
      semanticdbVersion = None,
      supportedScalaVersions = None,
      javaSemanticdbVersion = None,
      enableBestEffortMode = None
    )

    implicit val codec: JsonValueCodec[BloopExtraBuildParams] =
      JsonCodecMaker.makeWithRequiredCollectionFields
  }

  final case class StopClientCachingParams(originId: String)
  object StopClientCachingParams {
    implicit val codec: JsonValueCodec[StopClientCachingParams] =
      JsonCodecMaker.makeWithRequiredCollectionFields
  }

  object stopClientCaching
      extends Endpoint[StopClientCachingParams, Unit]("bloop/stopClientCaching")(
        StopClientCachingParams.codec,
        Endpoint.unitCodec
      )

  // Incremental compilation debugging endpoint definitions
  final case class DebugIncrementalCompilationParams(
      targets: List[BuildTargetIdentifier]
  )

  object DebugIncrementalCompilationParams {
    implicit val codec: JsonValueCodec[DebugIncrementalCompilationParams] =
      JsonCodecMaker.makeWithRequiredCollectionFields
  }

  final case class IncrementalCompilationDebugInfo(
      target: BuildTargetIdentifier,
      analysisInfo: Option[AnalysisDebugInfo],
      allFileHashes: List[FileHashInfo],
      lastCompilationInfo: String,
      maybeFailedCompilation: String
  )

  object IncrementalCompilationDebugInfo {
    implicit val codec: JsonValueCodec[IncrementalCompilationDebugInfo] =
      JsonCodecMaker.makeWithRequiredCollectionFields
  }

  final case class AnalysisDebugInfo(
      lastModified: Long,
      sourceFiles: Int,
      classFiles: Int,
      internalDependencies: Int,
      externalDependencies: Int,
      location: Uri,
      excludedFiles: List[String]
  )

  object AnalysisDebugInfo {
    implicit val codec: JsonValueCodec[AnalysisDebugInfo] =
      JsonCodecMaker.makeWithRequiredCollectionFields
  }

  final case class FileHashInfo(
      uri: Uri,
      currentHash: Int,
      analysisHash: Option[Int],
      lastModified: Long,
      exists: Boolean
  )

  object FileHashInfo {
    implicit val codec: JsonValueCodec[FileHashInfo] =
      JsonCodecMaker.makeWithRequiredCollectionFields
  }

  final case class DebugIncrementalCompilationResult(
      debugInfos: List[IncrementalCompilationDebugInfo]
  )

  object DebugIncrementalCompilationResult {
    implicit val codec: JsonValueCodec[DebugIncrementalCompilationResult] =
      JsonCodecMaker.makeWithRequiredCollectionFields
  }

  object debugIncrementalCompilation
      extends Endpoint[DebugIncrementalCompilationParams, DebugIncrementalCompilationResult](
        "bloop/debugIncrementalCompilation"
      )(
        DebugIncrementalCompilationParams.codec,
        JsonCodecMaker.makeWithRequiredCollectionFields
      )
}
