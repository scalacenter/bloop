package bloop.bsp

import ch.epfl.scala.bsp.Uri
import io.circe.derivation._
import io.circe.{Decoder, RootEncoder}

import scala.meta.jsonrpc.Endpoint

object BloopBspDefinitions {
  final case class BloopExtraBuildParams(
      ownsBuildFiles: Option[Boolean],
      clientClassesRootDir: Option[Uri],
      semanticdbVersion: Option[String],
      supportedScalaVersions: Option[List[String]],
      javaSemanticdbVersion: Option[String]
  )

  object BloopExtraBuildParams {
    val empty = BloopExtraBuildParams(
      ownsBuildFiles = None,
      clientClassesRootDir = None,
      semanticdbVersion = None,
      supportedScalaVersions = None,
      javaSemanticdbVersion = None
    )

    val encoder: RootEncoder[BloopExtraBuildParams] = deriveEncoder
    val decoder: Decoder[BloopExtraBuildParams] = deriveDecoder
  }

  final case class StopClientCachingParams(originId: String)
  object StopClientCachingParams {
    val encoder: RootEncoder[StopClientCachingParams] = deriveEncoder
    val decoder: Decoder[StopClientCachingParams] = deriveDecoder
  }

  object stopClientCaching
      extends Endpoint[StopClientCachingParams, Unit]("bloop/stopClientCaching") (
        StopClientCachingParams.decoder,
        StopClientCachingParams.encoder,
        implicitly[Decoder[Unit]],
        implicitly[RootEncoder[Unit]]
      )
}
