package bloop.bsp

import ch.epfl.scala.bsp
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

  final val ScalaCompileReportKind = "scala-compile-report"
  case class ScalaCompileReport(
      errors: Int,
      warnings: Int,
      isCompilationNoop: Boolean,
      compilationHashes: Map[String, Int]
  )

  object ScalaCompileReport {
    implicit val codec: JsonValueCodec[ScalaCompileReport] =
      JsonCodecMaker.makeWithRequiredCollectionFields
  }

  object stopClientCaching
      extends Endpoint[StopClientCachingParams, Unit]("bloop/stopClientCaching")(
        StopClientCachingParams.codec,
        Endpoint.unitCodec
      )

  /**
   * Why a reload did not happen, reported as data so clients decide from `retryable` instead of
   * matching on the message.
   */
  final case class ReloadAnalysisError(reason: String, retryable: Boolean)
  object ReloadAnalysisError {
    implicit val codec: JsonValueCodec[ReloadAnalysisError] =
      JsonCodecMaker.makeWithRequiredCollectionFields
  }

  final case class ReloadAnalysisParams(targets: Option[List[bsp.BuildTargetIdentifier]])
  object ReloadAnalysisParams {
    implicit val codec: JsonValueCodec[ReloadAnalysisParams] =
      JsonCodecMaker.makeWithRequiredCollectionFields
  }

  /**
   * Re-reads the compilation state persisted on disk, which `workspace/reload` does not: that
   * request must keep working while the build compiles, whereas importing state needs it idle.
   */
  object reloadAnalysis
      extends Endpoint[ReloadAnalysisParams, Unit]("bloop/reloadAnalysis")(
        ReloadAnalysisParams.codec,
        Endpoint.unitCodec
      )
}
