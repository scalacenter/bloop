package buildpress.config

import java.net.URI
import java.nio.file.Path

object Config {
  case class RepoCacheEntries(repos: List[RepoCacheEntry])

  case class HashedPath(path: Path, hash: Int)

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

  case class RepoCacheEntry(id: String, uri: URI, localPath: Path, hashes: BuildSettingsHashes)

  case class RepoCacheFile(version: String, cache: RepoCacheEntries)
  object RepoCacheFile {
    // We cannot have the version coming from the build tool
    final val LatestVersion = "1.0.0"
  }

  def toStr(all: RepoCacheFile): String = {
    val f = ConfigEncoderDecoders.repoCacheFileEncoder(all)
    Printer.spaces4.copy(dropNullValues = true).pretty(f)
  }

  def write(all: RepoCacheFile, target: Path): Unit = {
    Files.write(target, toStr(all).getBytes(StandardCharsets.UTF_8))
    ()
  }

  def readBuildpressConfig(file: Path): Either[String, RepoCacheFile] = {
    val cfgBytes: Array[Byte] = Files.readAllBytes(file)
    val cfg = new String(cfgBytes, StandardCharsets.UTF_8)
    import io.circe.parser._
    import ConfigEncoderDecoders.repoCacheFileDecoder
    decode[RepoCacheFile](cfg).left.map(io.circe.Error.showError.show)
  }
}
