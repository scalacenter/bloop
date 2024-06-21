package bloop.cli

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

final case class BloopJson(javaOptions: List[String] = Nil)

object BloopJson {
  val codec: JsonValueCodec[BloopJson] = JsonCodecMaker.make
}
