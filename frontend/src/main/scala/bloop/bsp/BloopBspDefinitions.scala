package bloop.bsp

import ch.epfl.scala.bsp

import io.circe.Json
import io.circe.{RootEncoder, Decoder}
import io.circe.derivation._

final case class BloopExtraBuildParams(
    clientClassesRootDir: Option[bsp.Uri],
    semanticdbVersion: Option[String],
    supportedScalaVersions: Option[List[String]]
)

object BloopExtraBuildParams {
  val empty = BloopExtraBuildParams(
    clientClassesRootDir = None,
    semanticdbVersion = None,
    supportedScalaVersions = None
  )

  val encoder: RootEncoder[BloopExtraBuildParams] = deriveEncoder
  val decoder: Decoder[BloopExtraBuildParams] = deriveDecoder
}

final case class BloopCompileSnippetParams(
    sources: List[Array[Byte]],
    config: Json
)

object BloopCompileSnippetParams {
  val encoder: RootEncoder[BloopCompileSnippetParams] = deriveEncoder
  val decoder: Decoder[BloopCompileSnippetParams] = deriveDecoder
}

final case class BloopCompileSnippetResult(
    result: bsp.CompileResult,
    products: List[bsp.Uri]
)

object BloopCompileSnippetResult {
  val encoder: RootEncoder[BloopCompileSnippetResult] = deriveEncoder
  val decoder: Decoder[BloopCompileSnippetResult] = deriveDecoder
}
