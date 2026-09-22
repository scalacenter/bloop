package bloop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Optional

import scala.util.Properties

import bloop.PortableAnalysis.Invalid
import bloop.PortableAnalysis.NotAToken
import bloop.PortableAnalysis.Resolved
import bloop.PortableAnalysis.Roots
import bloop.io.AbsolutePath

import org.junit.Assert._
import org.junit.Assume.assumeFalse
import org.junit.Test
import sbt.internal.inc.Analysis
import sbt.internal.inc.Analysis.NonLocalProduct
import sbt.internal.inc.Compilation
import sbt.internal.inc.Compilations
import sbt.internal.inc.CompileOutput
import sbt.internal.inc.ConcreteAnalysisContents
import sbt.internal.inc.FileAnalysisStore
import sbt.internal.inc.SourceInfos
import sbt.internal.inc.bloop.internal.BloopStamps
import sbt.util.InterfaceUtil
import xsbti.Position
import xsbti.Severity
import xsbti.T2
import xsbti.VirtualFileRef
import xsbti.compile.CompileOrder
import xsbti.compile.FileHash
import xsbti.compile.MiniOptions
import xsbti.compile.MiniSetup

class PortableAnalysisSpec {
  private def withTempDir[T](f: Path => T): T = {
    val dir = Files.createTempDirectory("portable-analysis")
    try f(dir)
    finally bloop.io.Paths.delete(AbsolutePath(dir))
  }

  private def mkdir(parent: Path, name: String): Path =
    Files.createDirectories(parent.resolve(name))

  @Test
  def tokenisesPathsUnderRootsMostSpecificFirst(): Unit = withTempDir { dir =>
    val base = mkdir(dir, "ws")
    val cache = mkdir(base, "cache")
    val roots = Roots(List("BASE" -> base, "CSR_CACHE" -> cache))
    assertEquals(Some("${BASE}/src/A.scala"), roots.toToken(base.resolve("src").resolve("A.scala")))
    assertEquals(Some("${CSR_CACHE}/x.jar"), roots.toToken(cache.resolve("x.jar")))
    assertEquals(Some("${BASE}"), roots.toToken(base))
    assertEquals(None, roots.toToken(dir.resolve("elsewhere").resolve("B.scala")))
  }

  @Test
  def tokenMatchingIsSegmentAware(): Unit = withTempDir { dir =>
    val base = mkdir(dir, "proj")
    val roots = Roots(List("BASE" -> base))
    assertEquals(None, roots.toToken(dir.resolve("proj2").resolve("A.scala")))
    assertEquals(None, roots.toToken(Paths.get("/tmp/dummy")))
  }

  @Test
  def relativeAndTokenInputsAreLeftAlone(): Unit = withTempDir { dir =>
    val roots = Roots(List("BASE" -> dir))
    assertEquals(None, roots.toToken(Paths.get("${BASE}/x")))
    assertEquals(None, roots.toToken(Paths.get("rt.jar")))
    assertEquals(None, roots.toTokenString("${BASE}/x"))
    assertEquals(None, roots.toTokenString(""))
  }

  @Test
  def resolvesTokensWithTheDerivedSpelling(): Unit = withTempDir { dir =>
    val base = mkdir(dir, "ws")
    val roots = Roots(List("BASE" -> base))
    val expected = base.resolve("src").resolve("A.scala")
    assertEquals(Resolved(expected), roots.resolve("${BASE}/src/A.scala"))
    assertEquals(Resolved(expected), roots.resolve("${BASE}\\src\\A.scala"))
    assertEquals(Resolved(base), roots.resolve("${BASE}"))
    assertEquals(NotAToken, roots.resolve(expected.toString))
    assertEquals(NotAToken, roots.resolve("rt.jar"))
  }

  @Test
  def rejectsUnknownKeysAndParentSegments(): Unit = withTempDir { dir =>
    val roots = Roots(List("BASE" -> dir))
    def assertInvalid(id: String): Unit = roots.resolve(id) match {
      case Invalid(reason) =>
        assertTrue(s"Reason should mention the input: $reason", reason.nonEmpty)
      case other => fail(s"Expected Invalid for '$id', got $other")
    }
    assertInvalid("${NOPE}/x")
    assertInvalid("${BASE}/../x")
    assertInvalid("${BASE}/a/../../x")
    assertInvalid("${BASE}x")
    assertInvalid("${}/x")
    assertInvalid("${BASE")
  }

  @Test
  def canonicalSpellingMatchesOnWriteAndDerivedSpellingOnRead(): Unit = withTempDir { dir =>
    assumeFalse("symbolic links need privileges on Windows", Properties.isWin)
    val real = mkdir(dir, "real")
    val link = Files.createSymbolicLink(dir.resolve("link"), real)
    val roots = Roots(List("BASE" -> link))
    // Bloop canonicalises some paths (e.g. internal classes dirs); both spellings must tokenise
    val canonical = real.toRealPath()
    assertEquals(Some("${BASE}/A.scala"), roots.toToken(canonical.resolve("A.scala")))
    assertEquals(Some("${BASE}/A.scala"), roots.toToken(link.resolve("A.scala")))
    // ...but tokens resolve to the spelling the root was derived from
    assertEquals(Resolved(link.resolve("A.scala")), roots.resolve("${BASE}/A.scala"))
  }

  @Test
  def mapsOptionStringsAnywhereWithABoundary(): Unit = withTempDir { dir =>
    val base = mkdir(dir, "ws")
    val roots = Roots(List("BASE" -> base))
    val javac = s"-Xplugin:semanticdb -sourceroot:$base -targetroot:javac-classes-directory"
    assertEquals(
      "-Xplugin:semanticdb -sourceroot:${BASE} -targetroot:javac-classes-directory",
      roots.mapOptionString(javac)
    )
    assertEquals(javac, roots.resolveOptionString(roots.mapOptionString(javac)))
    assertEquals(
      "-P:semanticdb:sourceroot:${BASE}",
      roots.mapOptionString(s"-P:semanticdb:sourceroot:$base")
    )
    val plugin = s"-Xplugin:${base.resolve("plugin.jar")}"
    val pluginToken = "-Xplugin:${BASE}" + java.io.File.separator + "plugin.jar"
    assertEquals(pluginToken, roots.mapOptionString(plugin))
    assertEquals(plugin, roots.resolveOptionString(pluginToken))
    val sibling = s"-Xplugin:${base}2/plugin.jar"
    assertEquals(sibling, roots.mapOptionString(sibling))
    assertEquals("-deprecation", roots.mapOptionString("-deprecation"))
    assertEquals("-Dfoo=${HOME}/x", roots.resolveOptionString("-Dfoo=${HOME}/x"))
    val once = roots.mapOptionString(javac)
    assertEquals(once, roots.mapOptionString(once))
    assertEquals(javac, roots.resolveOptionString(roots.resolveOptionString(once)))
  }

  @Test
  def writeSideNeverThrows(): Unit = withTempDir { dir =>
    val roots = Roots(List("BASE" -> dir))
    val inputs = List("", "${", "${BASE", "a:b", "C:\\", "\u0000", "rt.jar", "//", " ")
    inputs.foreach { s =>
      val _ = (roots.toTokenString(s), roots.mapOptionString(s))
    }
  }

  @Test
  def parsesExtraRootsProperty(): Unit = {
    val parsed = PortableAnalysis.parseRoots(Some("K=/a, BAD ,=/x,K2=/b,K3="))
    assertEquals(List("K" -> Paths.get("/a"), "K2" -> Paths.get("/b")), parsed)
    assertEquals(Nil, PortableAnalysis.parseRoots(None))
    assertEquals(Nil, PortableAnalysis.parseRoots(Some("")))
  }

  @Test
  def derivedRootsIncludeBaseAndSkipMissingOnes(): Unit = withTempDir { dir =>
    val base = mkdir(dir, "ws")
    val extra =
      List("MISSING" -> dir.resolve("nope"), "EXTRA" -> dir, "BASE" -> dir.resolve("nope"))
    val roots = Roots.derive(base, extra)
    val keys = roots.entries.map(_.key)
    assertTrue(keys.toString, keys.contains("BASE"))
    assertTrue(keys.toString, keys.contains("EXTRA"))
    assertTrue(keys.toString, keys.contains("JAVA_HOME"))
    assertFalse(keys.toString, keys.contains("MISSING"))
    // An extra root never overrides a derived one with a path that does not exist
    assertEquals(Some("${BASE}/A.scala"), roots.toToken(base.resolve("A.scala")))
    assertFalse(PortableAnalysis.enabled)
  }

  private def machine(dir: Path, name: String): Machine = {
    val base = mkdir(dir, name)
    Machine(base, mkdir(dir, s"$name-cache"), mkdir(base, "out"))
  }

  private def positionIn(path: Path): Position = new Position {
    override def line(): Optional[Integer] = Optional.empty()
    override def lineContent(): String = "class A"
    override def offset(): Optional[Integer] = Optional.empty()
    override def pointer(): Optional[Integer] = Optional.empty()
    override def pointerSpace(): Optional[String] = Optional.empty()
    override def sourcePath(): Optional[String] = Optional.of(path.toString)
    override def sourceFile(): Optional[java.io.File] = Optional.of(path.toFile)
  }

  private val stamp = BloopStamps.fromBloopHashToZincHash(1234)

  private def analysisFor(m: Machine): (Analysis, MiniSetup) = {
    val problem =
      InterfaceUtil.problem(
        "typer",
        positionIn(m.src),
        "unused",
        Severity.Warn,
        None,
        None,
        Nil,
        Nil
      )
    val info = SourceInfos.makeInfo(List(problem), Nil, Nil)
    val output = CompileOutput(m.out)
    val analysis = Analysis.empty
      .addSource(
        m.ref(m.src),
        Nil,
        stamp,
        info,
        List(NonLocalProduct("A", "A", m.ref(m.classFile), stamp)),
        Nil,
        Nil,
        Nil,
        List((m.ref(m.jar), "scala.Predef", stamp))
      )
      .copy(compilations = Compilations.empty.add(Compilation(0L, output)))
    val options = MiniOptions.of(
      Array(FileHash.of(m.jar, 7)),
      m.scalacOptions.toArray,
      m.javacOptions.toArray
    )
    val setup =
      MiniSetup.of(
        output,
        options,
        "2.13.18",
        CompileOrder.Mixed,
        true,
        Array.empty[T2[String, String]]
      )
    (analysis, setup)
  }

  private def slashed(path: Path): String = path.toString.replace('\\', '/')

  @Test
  def roundTripRebasesEveryMappedFieldAndKeepsProblemPositions(): Unit = withTempDir { dir =>
    val a = machine(dir, "a")
    val b = machine(dir, "b")
    val (analysis, setup) = analysisFor(a)
    val file = dir.resolve("a-analysis.bin").toFile
    FileAnalysisStore
      .binary(file, PortableAnalysis.writeMappers(a.roots))
      .set(ConcreteAnalysisContents(analysis, setup))

    // The persisted form really contains tokens
    val raw = FileAnalysisStore.binary(file).get.get
    val rawAnalysis = raw.getAnalysis.asInstanceOf[Analysis]
    assertEquals(Set("${BASE}/src/A.scala"), rawAnalysis.stamps.sources.keySet.map(_.id))
    assertEquals(Set("${BASE}/out/A.class"), rawAnalysis.stamps.products.keySet.map(_.id))
    assertEquals(Set("${CSR_CACHE}/lib.jar"), rawAnalysis.stamps.libraries.keySet.map(_.id))
    assertEquals("${BASE}/out", slashed(raw.getMiniSetup.output.getSingleOutputAsPath.get))
    assertEquals("${CSR_CACHE}/lib.jar", slashed(raw.getMiniSetup.options.classpathHash.head.file))
    assertTrue(raw.getMiniSetup.options.scalacOptions.contains("-P:semanticdb:sourceroot:${BASE}"))

    // Reading on "machine b" rebases everything that is mapped, exactly once
    val readSide = PortableAnalysis.readMappers(b.roots)
    val contents = FileAnalysisStore.binary(file, readSide.mappers).get.get
    val read = contents.getAnalysis.asInstanceOf[Analysis]
    assertEquals(Set(b.ref(b.src)), read.stamps.sources.keySet)
    assertEquals(Set(b.ref(b.classFile)), read.stamps.products.keySet)
    assertEquals(Set(b.ref(b.jar)), read.stamps.libraries.keySet)
    assertEquals(stamp.toString, read.stamps.sources(b.ref(b.src)).toString)
    assertEquals(Set(b.ref(b.classFile)), read.relations.products(b.ref(b.src)))
    assertEquals(Set(b.ref(b.jar)), read.relations.libraryDeps(b.ref(b.src)))
    assertEquals(b.out, read.compilations.allCompilations.head.getOutput.getSingleOutputAsPath.get)
    val setupB = contents.getMiniSetup
    assertEquals(b.out, setupB.output.getSingleOutputAsPath.get)
    assertEquals(b.jar, setupB.options.classpathHash.head.file)
    assertEquals(b.scalacOptions, setupB.options.scalacOptions.toList)
    assertEquals(b.javacOptions, setupB.options.javacOptions.toList)
    assertEquals(None, readSide.failure)

    // Documented limitation: diagnostic positions are not rebased
    val problem = read.infos.get(b.ref(b.src)).getReportedProblems.head
    assertEquals(Optional.of(a.src.toString), problem.position.sourcePath)
  }

  @Test
  def absoluteAnalysesPassThroughTheReadMapperUnchanged(): Unit = withTempDir { dir =>
    val a = machine(dir, "a")
    val b = machine(dir, "b")
    val (analysis, setup) = analysisFor(a)
    val file = dir.resolve("a-analysis.bin").toFile
    FileAnalysisStore.binary(file).set(ConcreteAnalysisContents(analysis, setup))
    val readSide = PortableAnalysis.readMappers(b.roots)
    val contents = FileAnalysisStore.binary(file, readSide.mappers).get.get
    val read = contents.getAnalysis.asInstanceOf[Analysis]
    assertEquals(Set(a.ref(a.src)), read.stamps.sources.keySet)
    assertEquals(Set(a.ref(a.jar)), read.stamps.libraries.keySet)
    assertEquals(a.out, contents.getMiniSetup.output.getSingleOutputAsPath.get)
    assertEquals(a.scalacOptions, contents.getMiniSetup.options.scalacOptions.toList)
    assertEquals(None, readSide.failure)
  }

  @Test
  def unknownRootMakesTheStoreReturnEmptyAndRecordsTheReason(): Unit = withTempDir { dir =>
    val a = machine(dir, "a")
    val b = machine(dir, "b")
    val (analysis, setup) = analysisFor(a)
    val file = dir.resolve("a-analysis.bin").toFile
    val foreignRoots = Roots(List("NOPE" -> a.base, "CSR_CACHE" -> a.cache))
    FileAnalysisStore
      .binary(file, PortableAnalysis.writeMappers(foreignRoots))
      .set(ConcreteAnalysisContents(analysis, setup))
    val readSide = PortableAnalysis.readMappers(b.roots)
    assertFalse(FileAnalysisStore.binary(file, readSide.mappers).get.isPresent)
    assertTrue(readSide.failure.toString, readSide.failure.exists(_.contains("NOPE")))
  }

  @Test
  def parentSegmentsAreRejectedByTheReadMapper(): Unit = withTempDir { dir =>
    val a = machine(dir, "a")
    val readSide = PortableAnalysis.readMappers(a.roots)
    val reader = readSide.mappers.getReadMapper
    def assertRejected(op: => Any): Unit = {
      try { op; fail("expected UnresolvableToken") }
      catch { case _: PortableAnalysis.UnresolvableToken => () }
    }
    assertRejected(reader.mapSourceFile(VirtualFileRef.of("${BASE}/../x")))
    assertRejected(reader.mapOutputDir(Paths.get("${BASE}/../x")))
    assertRejected(reader.mapClasspathEntry(Paths.get("${NOPE}/x.jar")))
    assertTrue(readSide.failure.toString, readSide.failure.exists(_.contains("escapes")))
    // Absolute inputs and unknown tokens inside option strings are left alone
    assertEquals(a.ref(a.src), reader.mapSourceFile(a.ref(a.src)))
    assertEquals("-Dfoo=${HOME}/x", reader.mapScalacOption("-Dfoo=${HOME}/x"))
  }
}

// A tiny "machine" for PortableAnalysisSpec: a workspace with a source, an output dir with a class file, and a cache
private[bloop] final case class Machine(base: Path, cache: Path, out: Path) {
  def src: Path = base.resolve("src").resolve("A.scala")
  def classFile: Path = out.resolve("A.class")
  def jar: Path = cache.resolve("lib.jar")
  def plugin: Path = cache.resolve("plugin.jar")
  def roots: Roots = Roots(List("BASE" -> base, "CSR_CACHE" -> cache))
  def ref(path: Path): VirtualFileRef = VirtualFileRef.of(path.toString)
  def scalacOptions: List[String] =
    List(s"-Xplugin:$plugin", s"-P:semanticdb:sourceroot:$base", "-deprecation")
  def javacOptions: List[String] =
    List(s"-Xplugin:semanticdb -sourceroot:$base -targetroot:javac-classes-directory")
}
