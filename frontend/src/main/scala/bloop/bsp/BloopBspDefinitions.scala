package bloop.bsp

import ch.epfl.scala.bsp.Uri
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader
import com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter
import jsonrpc4s.Endpoint

object BloopBspDefinitions {
  final case class BloopExtraBuildParams(
      ownsBuildFiles: Option[Boolean],
      clientClassesRootDir: Option[Uri],
      semanticdbVersion: Option[String],
      supportedScalaVersions: Option[List[String]]
  )

  object BloopExtraBuildParams {
    val empty = BloopExtraBuildParams(
      ownsBuildFiles = None,
      clientClassesRootDir = None,
      semanticdbVersion = None,
      supportedScalaVersions = None
    )

    implicit val codec: JsonValueCodec[BloopExtraBuildParams] = JsonCodecMaker.makeWithRequiredCollectionFields
  }

  final case class StopClientCachingParams(originId: String)
  object StopClientCachingParams {
    implicit val codec: JsonValueCodec[StopClientCachingParams] = JsonCodecMaker.makeWithRequiredCollectionFields
  }

  object stopClientCaching
      extends Endpoint[StopClientCachingParams, Unit]("bloop/stopClientCaching") (
        StopClientCachingParams.codec,
        Endpoint.unitCodec
      )
}
