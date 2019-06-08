package buildpress.config

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.Try
import io.circe.generic.JsonCodec
import io.circe.{Decoder, Encoder, Printer}

object Config {
  val BuildpressCacheFileName = "buildpress-repo-cache.json"

  implicit val pathEnc: Encoder[Path] =
    Encoder.encodeString.contramap[Path](_.toString)
  implicit val pathDec: Decoder[Path] =
    Decoder.decodeString.emapTry(s => Try(Paths.get(s)))

  implicit val uriEnc: Encoder[URI] =
    Encoder.encodeString.contramap[URI](_.toString)
  implicit val uriDec: Decoder[URI] =
    Decoder.decodeString.emapTry(s => Try(URI.create(s)))

  @JsonCodec
  case class RepoCacheEntries(repos: List[RepoCacheEntry])

  @JsonCodec
  case class HashedPath(path: Path, hash: Int)

  @JsonCodec
  final case class BuildSettingsHashes(individual: List[HashedPath]) {
    private lazy val fastHash: Int =
      scala.util.hashing.MurmurHash3.unorderedHash(individual.map(_.hash))

    override def hashCode(): Int = {
      fastHash.##
    }

    override def equals(other: Any): Boolean = {
      other match {
        case that: BuildSettingsHashes =>
          this.fastHash == that.fastHash || this.individual == that.individual
        case _ => false
      }
    }

    override def toString: String = s"BuildSettingsHashes($fastHash, $individual)"
  }

  @JsonCodec
  case class RepoCacheEntry(id: String, uri: URI, localPath: Path, hashes: BuildSettingsHashes)

  @JsonCodec
  case class RepoCacheFile(version: String, cache: RepoCacheEntries)

  object RepoCacheFile {
    // We cannot have the version coming from the build tool
    final val LatestVersion = "1.0.0"
  }

  def toStr(all: RepoCacheFile): String = {
    Printer.spaces4
      .copy(dropNullValues = true)
      .pretty(
        Encoder[RepoCacheFile].apply(all)
      )
  }

  def write(all: RepoCacheFile, target: Path): Unit = {
    Files.write(target, toStr(all).getBytes(StandardCharsets.UTF_8))
    ()
  }

  def readBuildpressConfig(file: Path): Either[String, RepoCacheFile] = {
    val cfgBytes: Array[Byte] = Files.readAllBytes(file)
    val cfg = new String(cfgBytes, StandardCharsets.UTF_8)
    import io.circe.parser._
    decode[RepoCacheFile](cfg).left.map(io.circe.Error.showError.show)
  }
}
