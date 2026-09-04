package bloop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.util.Try

import bloop.util.JavaRuntime

import xsbti.VirtualFileRef
import xsbti.compile.MiniSetup
import xsbti.compile.analysis.ReadMapper
import xsbti.compile.analysis.ReadWriteMappers
import xsbti.compile.analysis.Stamp
import xsbti.compile.analysis.WriteMapper

/**
 * Makes persisted Zinc analyses independent of absolute paths.
 *
 * On write, paths under a known root become `${KEY}/relative` tokens; on read, tokens resolve
 * to the roots of the current machine. Only the persisted form changes: the in-memory analysis
 * keeps absolute paths, so the rest of the compilation pipeline is unaffected.
 */
object PortableAnalysis {
  val EnabledProperty = "bloop.analysis.portable"
  val RootsProperty = "bloop.analysis.roots"

  /** Read per call so that a long-lived server (and tests) observe property changes. */
  def enabled: Boolean = java.lang.Boolean.getBoolean(EnabledProperty)

  /** Extra roots from `bloop.analysis.roots=KEY=/path,KEY2=/path`; malformed entries are skipped. */
  def extraRoots: List[(String, Path)] = parseRoots(sys.props.get(RootsProperty))

  def parseRoots(value: Option[String]): List[(String, Path)] = {
    value.toList.flatMap(_.split(',')).flatMap { entry =>
      entry.split("=", 2) match {
        case Array(rawKey, rawPath) =>
          val key = rawKey.trim
          val path = rawPath.trim
          if (key.isEmpty || path.isEmpty || !isValidKey(key)) None
          else Try(Paths.get(path)).toOption.map(p => key -> p)
        case _ => None
      }
    }
  }

  private def isValidKey(key: String): Boolean =
    key.forall(c => c.isLetterOrDigit || c == '_')

  /** Mappers for persisting: tokenise on write, identity on read. Never throws. */
  def writeMappers(roots: Roots): ReadWriteMappers =
    new ReadWriteMappers(ReadMapper.getEmptyMapper, new TokenisingWriteMapper(roots))

  /**
   * Mappers for loading: resolve tokens on read, identity on write. A token that cannot be
   * resolved is signalled by throwing from inside the mapper, which Zinc's store turns into an
   * empty result; [[ReadSide.failure]] keeps the first reason so the caller can report it.
   */
  def readMappers(roots: Roots): ReadSide = new ReadSide(new ResolvingReadMapper(roots))

  final class ReadSide private[PortableAnalysis] (reader: ResolvingReadMapper) {
    val mappers: ReadWriteMappers = new ReadWriteMappers(reader, WriteMapper.getEmptyMapper)
    def failure: Option[String] = reader.failure
  }

  final class UnresolvableToken(reason: String) extends RuntimeException(reason)

  private final class TokenisingWriteMapper(roots: Roots) extends WriteMapper {
    private def mapRef(ref: VirtualFileRef): VirtualFileRef =
      roots.toTokenString(ref.id).fold(ref)(VirtualFileRef.of)
    private def mapPath(path: Path): Path =
      roots.toToken(path).fold(path)(token => Paths.get(token))

    override def mapSourceFile(sourceFile: VirtualFileRef): VirtualFileRef = mapRef(sourceFile)
    override def mapBinaryFile(binaryFile: VirtualFileRef): VirtualFileRef = mapRef(binaryFile)
    override def mapProductFile(productFile: VirtualFileRef): VirtualFileRef = mapRef(productFile)
    override def mapOutputDir(outputDir: Path): Path = mapPath(outputDir)
    override def mapSourceDir(sourceDir: Path): Path = mapPath(sourceDir)
    override def mapClasspathEntry(classpathEntry: Path): Path = mapPath(classpathEntry)
    override def mapJavacOption(javacOption: String): String = roots.mapOptionString(javacOption)
    override def mapScalacOption(scalacOption: String): String =
      roots.mapOptionString(scalacOption)
    override def mapProductStamp(file: VirtualFileRef, productStamp: Stamp): Stamp = productStamp
    override def mapSourceStamp(file: VirtualFileRef, sourceStamp: Stamp): Stamp = sourceStamp
    override def mapBinaryStamp(file: VirtualFileRef, binaryStamp: Stamp): Stamp = binaryStamp
    // Zinc applies this hook before the per-field mappers on write and after them on read
    override def mapMiniSetup(miniSetup: MiniSetup): MiniSetup = miniSetup
  }

  private final class ResolvingReadMapper(roots: Roots) extends ReadMapper {
    @volatile private var firstFailure: Option[String] = None

    def failure: Option[String] = firstFailure

    private def reject(reason: String): Nothing = {
      if (firstFailure.isEmpty) firstFailure = Some(reason)
      throw new UnresolvableToken(reason)
    }

    private def mapRef(ref: VirtualFileRef): VirtualFileRef = roots.resolve(ref.id) match {
      case NotAToken => ref
      case Resolved(path) => VirtualFileRef.of(path.toString)
      case Invalid(reason) => reject(reason)
    }

    // Path-typed fields arrive already parsed, so detect tokens on their string form
    private def mapPath(path: Path): Path = roots.resolve(path.toString) match {
      case NotAToken => path
      case Resolved(resolved) => resolved
      case Invalid(reason) => reject(reason)
    }

    override def mapSourceFile(sourceFile: VirtualFileRef): VirtualFileRef = mapRef(sourceFile)
    override def mapBinaryFile(binaryFile: VirtualFileRef): VirtualFileRef = mapRef(binaryFile)
    override def mapProductFile(productFile: VirtualFileRef): VirtualFileRef = mapRef(productFile)
    override def mapOutputDir(outputDir: Path): Path = mapPath(outputDir)
    override def mapSourceDir(sourceDir: Path): Path = mapPath(sourceDir)
    override def mapClasspathEntry(classpathEntry: Path): Path = mapPath(classpathEntry)
    override def mapJavacOption(javacOption: String): String =
      roots.resolveOptionString(javacOption)
    override def mapScalacOption(scalacOption: String): String =
      roots.resolveOptionString(scalacOption)
    override def mapProductStamp(file: VirtualFileRef, productStamp: Stamp): Stamp = productStamp
    override def mapSourceStamp(file: VirtualFileRef, sourceStamp: Stamp): Stamp = sourceStamp
    override def mapBinaryStamp(file: VirtualFileRef, binaryStamp: Stamp): Stamp = binaryStamp
    override def mapMiniSetup(miniSetup: MiniSetup): MiniSetup = miniSetup
  }

  sealed trait Resolution
  case object NotAToken extends Resolution
  final case class Resolved(path: Path) extends Resolution
  final case class Invalid(reason: String) extends Resolution

  /**
   * A root known by `key`. `derived` is the spelling Bloop derived it from (the one config and
   * the compiler see); `spellings` adds the canonical spelling when it differs, so paths that
   * were canonicalised elsewhere (e.g. `/private/var` for `/var`) still match on write.
   */
  final case class Root(key: String, derived: Path, spellings: List[Path]) {
    def depth: Int = spellings.map(_.getNameCount).max
  }

  final class Roots private (val entries: List[Root]) {
    private val byKey: Map[String, Root] = entries.map(r => r.key -> r).toMap

    /** Tokenises an absolute path under a root; anything else is left for the caller unchanged. */
    def toToken(path: Path): Option[String] = {
      if (!path.isAbsolute) None
      else {
        val it = entries.iterator
        var result: Option[String] = None
        while (result.isEmpty && it.hasNext) {
          val root = it.next()
          root.spellings.find(sp => path.startsWith(sp)).foreach { sp =>
            val rest = sp.relativize(path).toString.replace('\\', '/')
            result = Some(token(root.key, rest))
          }
        }
        result
      }
    }

    /** Like [[toToken]] but total over strings: never throws, never touches existing tokens. */
    def toTokenString(id: String): Option[String] =
      if (id.isEmpty || id.startsWith("${")) None
      else Try(Paths.get(id)).toOption.flatMap(toToken)

    def resolve(id: String): Resolution = {
      if (!id.startsWith("${")) NotAToken
      else {
        val close = id.indexOf('}')
        if (close < 0) Invalid(s"malformed token '$id'")
        else {
          val key = id.substring(2, close)
          val rest = id.substring(close + 1)
          if (key.isEmpty) Invalid(s"malformed token '$id'")
          else if (!(rest.isEmpty || rest.startsWith("/") || rest.startsWith("\\")))
            Invalid(s"malformed token '$id'")
          else {
            byKey.get(key) match {
              case None => Invalid(s"unknown root '$key' in '$id'")
              case Some(root) =>
                val segments = rest.replace('\\', '/').split('/').filter(_.nonEmpty)
                if (segments.contains("..")) Invalid(s"'$id' escapes its root")
                else {
                  val resolved = segments.foldLeft(root.derived)(_.resolve(_))
                  if (resolved.startsWith(root.derived)) Resolved(resolved)
                  else Invalid(s"'$id' resolves outside its root")
                }
            }
          }
        }
      }
    }

    /**
     * Replaces every root spelling that is followed by a path boundary. The separator after
     * the root is preserved so that options round-trip byte for byte on the same OS.
     */
    def mapOptionString(option: String): String = {
      entries.foldLeft(option) { (acc, root) =>
        root.spellings.foldLeft(acc) { (acc, spelling) =>
          replaceAtBoundary(acc, spelling.toString, token(root.key, ""))
        }
      }
    }

    /** Replaces known `${KEY}` tokens with the derived spelling; unknown tokens are kept. */
    def resolveOptionString(option: String): String = {
      entries.foldLeft(option) { (acc, root) =>
        replaceAtBoundary(acc, token(root.key, ""), root.derived.toString)
      }
    }

    private def token(key: String, rest: String): String =
      if (rest.isEmpty) s"$${$key}" else s"$${$key}/$rest"

    private def isBoundary(c: Char): Boolean =
      c == '/' || c == '\\' || c == ':' || c == ';' || c == ',' || c == '=' || c == '"' ||
        c == '\'' || c.isWhitespace

    private def replaceAtBoundary(in: String, target: String, replacement: String): String = {
      if (target.isEmpty || !in.contains(target)) in
      else {
        val out = new java.lang.StringBuilder
        var from = 0
        var idx = in.indexOf(target, from)
        while (idx >= 0) {
          val end = idx + target.length
          val atBoundary = end == in.length || isBoundary(in.charAt(end))
          out.append(in, from, idx)
          out.append(if (atBoundary) replacement else target)
          from = end
          idx = in.indexOf(target, from)
        }
        out.append(in, from, in.length)
        out.toString
      }
    }
  }

  object Roots {

    /**
     * Builds the root map from `(key, path)` pairs: missing paths are dropped, a later entry
     * for the same key wins, and deeper roots come first so a nested root beats its parent.
     */
    def apply(entries: List[(String, Path)]): Roots = {
      val existing = entries.filter { case (_, p) => p.isAbsolute && Files.exists(p) }
      val lastPerKey = existing.groupBy(_._1).map { case (key, es) => key -> es.last._2 }
      val roots = existing.map(_._1).distinct.map { key =>
        val derived = lastPerKey(key)
        val canonical = Try(derived.toRealPath()).toOption.filter(_ != derived)
        Root(key, derived, derived :: canonical.toList)
      }
      new Roots(roots.sortBy(r => -r.depth))
    }

    /** Roots for a build whose workspace is `base`, mirroring sbt's `rootPaths`. */
    def derive(base: Path, extra: List[(String, Path)] = extraRoots): Roots = {
      val home = Paths.get(sys.props("user.home"))
      val defaults = List("BASE" -> base) ++
        coursierCache.map("CSR_CACHE" -> _).toList ++
        List(
          "IVY_HOME" -> home.resolve(".ivy2"),
          "SBT_BOOT" -> home.resolve(".sbt").resolve("boot"),
          "JAVA_HOME" -> JavaRuntime.home.underlying
        )
      Roots(defaults ++ extra)
    }

    // Resolving the cache location may create directories, so do it lazily and only once
    private lazy val coursierCache: Option[Path] =
      Try(coursierapi.Cache.create().getLocation.toPath).toOption
  }
}
