package bloop.nailgun
import java.nio.charset.StandardCharsets

object Protocol {
  val Version = "0.9.3"
  object Defaults {
    val Host = "127.0.01"
    val Port = 8212
  }

  object Time {
    val DefaultHeartbeatIntervalMillis = 500.toLong
    val SendThreadWaitTerminationMillis = 5000.toLong
  }

  object ChunkTypes {
    sealed abstract class ChunkType(repr: String) {
      lazy val toByteRepr: Byte =
        repr.getBytes(StandardCharsets.US_ASCII).apply(0)
    }

    final case object Stdin extends ChunkType("0")
    final case object Stdout extends ChunkType("1")
    final case object Stderr extends ChunkType("2")
    final case object StdinEOF extends ChunkType(".")
    final case object SendInput extends ChunkType("S")
    final case object Heartbeat extends ChunkType("H")
    final case object Environment extends ChunkType("E")
    final case object Directory extends ChunkType("D")
    final case object Command extends ChunkType("C")
    final case object Argument extends ChunkType("A")
    final case object LongArgument extends ChunkType("L")
    final case object Exit extends ChunkType("X")
  }
}
