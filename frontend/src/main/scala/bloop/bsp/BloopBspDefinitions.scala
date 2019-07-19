package bloop.bsp

import io.circe._
import io.circe.derivation._
import ch.epfl.scala.bsp.Uri

object BloopBspDefinitions {
  final case class BloopExtraBuildParams(
      clientClassesRootDir: Option[Uri],
      semanticdbVersion: Option[String],
      supportedScalaVersions: List[String],
      reapplySettings: Boolean
  )

  object BloopExtraBuildParams {
    val empty = BloopExtraBuildParams(
      clientClassesRootDir = None,
      semanticdbVersion = None,
      supportedScalaVersions = Nil,
      reapplySettings = false
    )
    val encoder: RootEncoder[BloopExtraBuildParams] = deriveEncoder
    val decoder: Decoder[BloopExtraBuildParams] = deriveDecoder
  }
}
