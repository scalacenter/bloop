package bloop.cli.options

import caseapp._
import coursier.cache.{CacheLogger, FileCache}

import scala.concurrent.duration.Duration
import coursier.util.Task

// format: off
final case class CoursierOptions(
  @HelpMessage("Specify a TTL for changing dependencies, such as snapshots")
  @ValueDescription("duration|Inf")
  @Hidden
    ttl: Option[String] = None,
  @HelpMessage("Set the coursier cache location")
  @ValueDescription("path")
  @Hidden
    cache: Option[String] = None
) {
  // format: on

  def coursierCache(logger: CacheLogger): FileCache[Task] = {
    var baseCache = FileCache().withLogger(logger)
    val ttlOpt    = ttl.map(_.trim).filter(_.nonEmpty).map(Duration(_))
    for (ttl0 <- ttlOpt)
      baseCache = baseCache.withTtl(ttl0)
    for (loc <- cache.filter(_.trim.nonEmpty))
      baseCache = baseCache.withLocation(loc)
    baseCache
  }
}

object CoursierOptions {
  lazy val parser: Parser[CoursierOptions]                           = Parser.derive
  implicit lazy val parserAux: Parser.Aux[CoursierOptions, parser.D] = parser
  implicit lazy val help: Help[CoursierOptions]                      = Help.derive
}
