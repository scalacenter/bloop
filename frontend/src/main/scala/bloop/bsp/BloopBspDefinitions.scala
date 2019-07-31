package bloop.bsp

import ch.epfl.scala.bsp.Uri

object BloopBspDefinitions {
  final case class BloopExtraBuildParams(
      clientClassesRootDir: Option[Uri],
      semanticdbVersion: Option[String],
      supportedScalaVersions: List[String]
  )

  object BloopExtraBuildParams {
    val empty = BloopExtraBuildParams(
      clientClassesRootDir = None,
      semanticdbVersion = None,
      supportedScalaVersions = Nil
    )

    import io.circe.{RootEncoder, Decoder}
    import io.circe.derivation._
    val encoder: RootEncoder[BloopExtraBuildParams] = deriveEncoder
    val decoder: Decoder[BloopExtraBuildParams] = deriveDecoder
  }
}
