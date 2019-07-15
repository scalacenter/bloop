package bloop.bsp

import io.circe._
import io.circe.derivation._
import ch.epfl.scala.bsp.Uri

object BloopBspDefinitions {
  final case class BloopExtraBuildParams(
      clientClassesRootDir: Option[Uri]
  )

  object BloopExtraBuildParams {
    val encoder: RootEncoder[BloopExtraBuildParams] = deriveEncoder
    val decoder: Decoder[BloopExtraBuildParams] = deriveDecoder
  }
}
