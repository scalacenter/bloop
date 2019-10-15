package buildpress.config

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.Try

import com.github.plokhotnyuk.jsoniter_scala.{core => jsoniter}
import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, JsonWriter, JsonReader}
import com.github.plokhotnyuk.jsoniter_scala.macros.{JsonCodecMaker, CodecMakerConfig}

object Config {
  val BuildpressCacheFileName = "repository-cache.json"

  implicit val codecPath: JsonValueCodec[Path] = new JsonValueCodec[Path] {
    val nullValue: Path = Paths.get("")
    def encodeValue(x: Path, out: JsonWriter): Unit = out.writeVal(x.toString)
    def decodeValue(in: JsonReader, default: Path): Path =
      if (in.isNextToken('"')) {
        in.rollbackToken()
        Try(Paths.get(in.readString(""))).toOption.getOrElse(nullValue)
      } else {
        in.rollbackToken()
        nullValue
      }
  }

  implicit val codecURI: JsonValueCodec[URI] = new JsonValueCodec[URI] {
    val nullValue: URI = null
    def encodeValue(x: URI, out: JsonWriter): Unit = out.writeVal(x.toString)
    def decodeValue(in: JsonReader, default: URI): URI =
      if (in.isNextToken('"')) {
        in.rollbackToken()
        Try(URI.create(in.readString(""))).toOption.getOrElse(nullValue)
      } else {
        in.rollbackToken()
        nullValue
      }
  }

  case class RepoCacheEntries(repos: List[RepoCacheEntry])
  object RepoCacheEntries {
    implicit val codecSettings: JsonValueCodec[RepoCacheEntries] =
      JsonCodecMaker.make[RepoCacheEntries](CodecMakerConfig)
  }

  case class HashedPath(path: Path, hash: Int)
  object HashedPath {
    implicit val codecSettings: JsonValueCodec[HashedPath] =
      JsonCodecMaker.make[HashedPath](CodecMakerConfig)
  }

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
  object BuildSettingsHashes {
    implicit val codecSettings: JsonValueCodec[BuildSettingsHashes] =
      JsonCodecMaker.make[BuildSettingsHashes](CodecMakerConfig)
  }

  case class RepoCacheEntry(id: String, uri: URI, localPath: Path, hashes: BuildSettingsHashes)
  object RepoCacheEntry {
    implicit val codecSettings: JsonValueCodec[RepoCacheEntry] =
      JsonCodecMaker.make[RepoCacheEntry](CodecMakerConfig)
  }

  case class RepoCacheFile(version: String, cache: RepoCacheEntries)

  object RepoCacheFile {
    // We cannot have the version coming from the build tool
    final val LatestVersion = "1.0.0"

    implicit val codecSettings: JsonValueCodec[RepoCacheFile] =
      JsonCodecMaker.make[RepoCacheFile](CodecMakerConfig)
  }

  def write(all: RepoCacheFile, target: Path): Unit = {
    val contents = jsoniter.writeToArray(all)
    Files.write(target, contents)
    ()
  }

  def readBuildpressConfig(file: Path): Either[String, RepoCacheFile] = {
    val bytes = Files.readAllBytes(file)
    Try(jsoniter.readFromArray[RepoCacheFile](bytes)).toEither.left.map(_.getMessage)
  }
}
