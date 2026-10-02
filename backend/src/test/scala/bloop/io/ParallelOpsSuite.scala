package bloop.io

import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._

import bloop.io.ParallelOps.CopyMode
import bloop.logging.RecordingLogger
import bloop.task.Task
import bloop.util.CrossPlatform

import monix.execution.Scheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ParallelOpsSuite {

  private val scheduler = Scheduler.Implicits.global
  private val tempDirs = mutable.ListBuffer.empty[Path]

  private def tracked(dir: Path): Path = {
    tempDirs += dir
    dir
  }

  private def newTempDir(): Path = tracked(Files.createTempDirectory("parallel"))

  @After
  def deleteTempDirs(): Unit = tempDirs.foreach(dir => Paths.delete(AbsolutePath(dir)))

  private def createRandomDirectory(): Path = {
    val from = newTempDir()
    val inputFile = from.resolve("text.scala")
    val text = "random\n" * 100
    Files.write(inputFile, text.getBytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    from
  }

  private def createDirectoryWithFiles(names: String*): Path = {
    val from = newTempDir()
    names.foreach(name => writeFile(from.resolve(name), s"contents of $name\n"))
    from
  }

  private def writeFile(path: Path, contents: String): Path = {
    Files.createDirectories(path.getParent)
    Files.write(path, contents.getBytes(StandardCharsets.UTF_8))
  }

  private def contentOf(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def run[T](task: Task[T]): T = Await.result(task.runAsync(scheduler), 15.seconds)

  // One worker so that a failing link is observed exactly once per walk
  private def linkConfig(mode: CopyMode): ParallelOps.CopyConfiguration =
    ParallelOps.CopyConfiguration(1, mode, Set.empty, Set.empty, linkFiles = true)

  private def copy(
      config: ParallelOps.CopyConfiguration,
      from: Path,
      to: Path,
      logger: RecordingLogger = new RecordingLogger()
  ): ParallelOps.FileWalk = {
    run(
      ParallelOps.copyDirectories(config)(
        from,
        to,
        scheduler,
        enableCancellation = false,
        logger
      )
    )
  }

  private def copyWith(linkFile: (Path, Path) => Unit)(
      config: ParallelOps.CopyConfiguration,
      from: Path,
      to: Path
  ): ParallelOps.FileWalk = {
    run(
      ParallelOps.copyDirectoriesWith(linkFile)(config)(
        from,
        to,
        scheduler,
        enableCancellation = false,
        new RecordingLogger(),
        Nil
      )
    )
  }

  private def countingLinker(counter: AtomicInteger): (Path, Path) => Unit = { (link, existing) =>
    counter.incrementAndGet()
    Files.createLink(link, existing)
    ()
  }

  private def assertLinked(from: Path, to: Path, name: String): Unit = {
    assertTrue(s"$name is not a link", Files.isSameFile(from.resolve(name), to.resolve(name)))
    assertEquals(contentOf(from.resolve(name)), contentOf(to.resolve(name)))
  }

  private def assertCopied(from: Path, to: Path, name: String): Unit = {
    assertTrue(s"$name is missing", Files.isRegularFile(to.resolve(name)))
    assertFalse(s"$name is a link", Files.isSameFile(from.resolve(name), to.resolve(name)))
    assertEquals(contentOf(from.resolve(name)), contentOf(to.resolve(name)))
  }

  // On Windows a transient handle of another process can fail a replace, which is logged
  private def assertNoErrors(logger: RecordingLogger): Unit = {
    if (!CrossPlatform.isWindows) assertTrue(logger.errors.mkString("\n"), logger.errors.isEmpty)
  }

  // Runs 101 walks into the same target at once; `from` is evaluated once per walk
  private def runOverlappingWalks(
      config: ParallelOps.CopyConfiguration,
      from: => Path,
      to: Path
  ): (Seq[ParallelOps.FileWalk], RecordingLogger) = {
    val logger = new RecordingLogger()
    val tasks =
      for (_ <- 0 to 100)
        yield ParallelOps.copyDirectories(config)(
          from,
          to,
          scheduler,
          enableCancellation = false,
          logger
        )
    (run(Task.gatherUnordered(tasks)), logger)
  }

  @Test
  def runMultipleCopies(): Unit = {
    for (linkFiles <- List(false, true)) {
      val from = createRandomDirectory()
      val to = newTempDir()
      val config =
        ParallelOps.CopyConfiguration(5, CopyMode.ReplaceExisting, Set.empty, Set.empty)

      val (walks, logger) = runOverlappingWalks(config.copy(linkFiles = linkFiles), from, to)

      assertEquals(s"linkFiles = $linkFiles", 101L, walks.size.toLong)
      if (linkFiles) {
        assertNoErrors(logger)
        assertLinked(from, to, "text.scala")
      }
    }
  }

  @Test
  def runMultipleCopiesFromDifferentSource(): Unit = {
    for (linkFiles <- List(false, true)) {
      val to = newTempDir()
      val config =
        ParallelOps.CopyConfiguration(100, CopyMode.ReplaceExisting, Set.empty, Set.empty)

      val sources = mutable.ListBuffer.empty[Path]
      def nextSource(): Path = {
        sources += createRandomDirectory()
        sources.last
      }

      val (walks, logger) =
        runOverlappingWalks(config.copy(linkFiles = linkFiles), nextSource(), to)

      assertEquals(s"linkFiles = $linkFiles", 101L, walks.size.toLong)
      if (linkFiles) {
        assertNoErrors(logger)
        // The target links whichever source was walked last, none of them was copied
        val target = to.resolve("text.scala")
        assertTrue(sources.exists(from => Files.isSameFile(from.resolve("text.scala"), target)))
      }
    }
  }

  @Test
  def linkModeHardLinksFiles(): Unit = {
    val from = createDirectoryWithFiles("A.class", "pkg/B.class")
    val to = newTempDir()

    val walk = copy(linkConfig(CopyMode.ReplaceExisting), from, to)

    assertLinked(from, to, "A.class")
    assertLinked(from, to, "pkg/B.class")
    assertEquals(Set(to.resolve("A.class"), to.resolve("pkg/B.class")), walk.target.toSet)
  }

  @Test
  def replaceExistingRelinksWithoutWritingThrough(): Unit = {
    val from = createDirectoryWithFiles("A.class")
    val to = newTempDir()
    val stale = writeFile(to.resolve("A.class"), "old")
    // A second name for the stale file: it must keep its contents after the replace
    val witness = Files.createLink(newTempDir().resolve("witness"), stale)

    copy(linkConfig(CopyMode.ReplaceExisting), from, to)

    assertLinked(from, to, "A.class")
    assertEquals("old", contentOf(witness))
    assertFalse(Files.isSameFile(witness, to.resolve("A.class")))
  }

  @Test
  def replacingAnAlreadyLinkedTargetIsANoOp(): Unit = {
    val from = createDirectoryWithFiles("A.class")
    val to = newTempDir()
    val links = new AtomicInteger(0)
    val config = linkConfig(CopyMode.ReplaceExisting)

    copyWith(countingLinker(links))(config, from, to)
    copyWith(countingLinker(links))(config, from, to)

    assertEquals(1L, links.get.toLong)
    assertLinked(from, to, "A.class")
  }

  @Test
  def replaceIfMetadataMismatchDoesNotRelinkLinkedFiles(): Unit = {
    val from = createDirectoryWithFiles("A.class", "B.class")
    val to = newTempDir()
    val links = new AtomicInteger(0)
    val config = linkConfig(CopyMode.ReplaceIfMetadataMismatch)

    copyWith(countingLinker(links))(config, from, to)
    assertEquals(2L, links.get.toLong)

    copyWith(countingLinker(links))(config, from, to)
    assertEquals(2L, links.get.toLong)
    assertLinked(from, to, "A.class")
    assertLinked(from, to, "B.class")
  }

  @Test
  def noReplaceKeepsExistingFiles(): Unit = {
    val from = createDirectoryWithFiles("A.class", "B.class")
    val to = newTempDir()
    writeFile(to.resolve("A.class"), "kept")

    copy(linkConfig(CopyMode.NoReplace), from, to)

    assertEquals("kept", contentOf(to.resolve("A.class")))
    assertFalse(Files.isSameFile(from.resolve("A.class"), to.resolve("A.class")))
    assertLinked(from, to, "B.class")
  }

  @Test
  def killSwitchFallsBackToCopies(): Unit = {
    val from = createDirectoryWithFiles("A.class")
    val to = newTempDir()
    val previous = sys.props.get(ParallelOps.HardLinksProperty)
    sys.props(ParallelOps.HardLinksProperty) = "false"
    try copy(linkConfig(CopyMode.ReplaceExisting), from, to)
    finally {
      previous match {
        case Some(value) => sys.props(ParallelOps.HardLinksProperty) = value
        case None => sys.props.remove(ParallelOps.HardLinksProperty); ()
      }
    }

    assertCopied(from, to, "A.class")
  }

  @Test
  def failedLinkDisablesLinkingForTheWalk(): Unit = {
    val failures = List[(Path, Path) => Throwable](
      (link, existing) =>
        new FileSystemException(link.toString, existing.toString, "Invalid cross-device link"),
      (_, _) => new UnsupportedOperationException("links are not supported")
    )

    for (failure <- failures) {
      val from = createDirectoryWithFiles("A.class", "B.class", "C.class")
      val to = newTempDir()
      val attempts = new AtomicInteger(0)
      val failingLinker: (Path, Path) => Unit = { (link, existing) =>
        attempts.incrementAndGet()
        throw failure(link, existing)
      }

      copyWith(failingLinker)(linkConfig(CopyMode.ReplaceExisting), from, to)

      val failureName = failure(from, to).getClass.getSimpleName
      assertEquals(s"after $failureName", 1L, attempts.get.toLong)
      List("A.class", "B.class", "C.class").foreach(name => assertCopied(from, to, name))
    }
  }

  @Test
  def deniedLinkCopiesThatFileOnly(): Unit = {
    val from = createDirectoryWithFiles("A.class", "B.class", "C.class")
    val to = newTempDir()
    val attempts = new AtomicInteger(0)
    // A denied link concerns one file, for example one held open, not the file store
    val linker: (Path, Path) => Unit = { (link, existing) =>
      attempts.incrementAndGet()
      if (link.getFileName.toString == "B.class") throw new AccessDeniedException(link.toString)
      Files.createLink(link, existing)
      ()
    }

    copyWith(linker)(linkConfig(CopyMode.ReplaceExisting), from, to)

    assertEquals(3L, attempts.get.toLong)
    assertLinked(from, to, "A.class")
    assertCopied(from, to, "B.class")
    assertLinked(from, to, "C.class")
  }

  @Test
  def symbolicLinksAreCopied(): Unit = {
    val from = createDirectoryWithFiles("A.class")
    val to = newTempDir()
    val linked = scala.util.Try(
      Files.createSymbolicLink(from.resolve("L.class"), from.resolve("A.class"))
    )
    // Creating symbolic links needs privileges on some systems
    assumeTrue(linked.isSuccess)

    copy(linkConfig(CopyMode.ReplaceExisting), from, to)

    assertLinked(from, to, "A.class")
    assertFalse(Files.isSymbolicLink(to.resolve("L.class")))
    assertEquals(contentOf(from.resolve("A.class")), contentOf(to.resolve("L.class")))
    assertFalse(Files.isSameFile(from.resolve("A.class"), to.resolve("L.class")))
  }

  @Test
  def crossDeviceLinksFallBackToCopies(): Unit = {
    // Only Linux offers a second file store out of the box; the test is skipped elsewhere
    val shm = java.nio.file.Paths.get("/dev/shm")
    assumeTrue(Files.isDirectory(shm) && Files.isWritable(shm))
    val from = createDirectoryWithFiles("A.class", "B.class")
    assumeTrue(Files.getFileStore(shm) != Files.getFileStore(from))
    val to = tracked(Files.createTempDirectory(shm, "parallel"))
    val logger = new RecordingLogger()

    copy(linkConfig(CopyMode.ReplaceExisting), from, to, logger)

    List("A.class", "B.class").foreach(name => assertCopied(from, to, name))
    assertNoErrors(logger)
  }

  @Test
  def resourcesAreNeverLinked(): Unit = {
    val from = createDirectoryWithFiles("app.conf")
    val to = newTempDir()
    val logger = new RecordingLogger()

    run(
      ParallelOps.copyResources(
        List(AbsolutePath(from)),
        AbsolutePath(to),
        linkConfig(CopyMode.ReplaceExisting),
        logger,
        scheduler
      )
    )

    assertCopied(from, to, "app.conf")
  }
}
