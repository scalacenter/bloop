package bloop.bsp

import scala.meta.jsonrpc.Endpoint

object BloopCodeSnippet extends BloopCodeSnippet

trait BloopCodeSnippet {
  object compile
      extends Endpoint[BloopCompileSnippetParams, BloopCompileSnippetResult](
        "bloopCodeSnippet/compile"
      )(
        // Add implicits explicitly, otherwise compiler doesn't find them (bug!!!)
        BloopCompileSnippetParams.decoder,
        BloopCompileSnippetParams.encoder,
        BloopCompileSnippetResult.decoder,
        BloopCompileSnippetResult.encoder
      )
}
