package bloop.io

import java.nio.file.Files
import java.nio.file.Path

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Success

import bloop.logging.RecordingLogger
import bloop.tracing.BraveTracer
import bloop.tracing.TraceProperties

import monix.execution.schedulers.TestScheduler
import org.junit.Assert._
import org.junit.Test
import sbt.internal.inc.bloop.internal.BloopStamps
import xsbti.compile.FileHash

class ClasspathHasherCancellationSpec {
  import ClasspathHasherCancellationSpec.WaitingRun

  private def runHasher(
      jar: Path,
      cancelCompilation: Promise[Unit],
      scheduler: TestScheduler,
      logger: RecordingLogger
  ): Future[Either[Unit, Vector[FileHash]]] = {
    val tracer = BraveTracer("classpath-hasher-test", TraceProperties.default)
    ClasspathHasher
      .hash(Array(AbsolutePath(jar)), 2, cancelCompilation, scheduler, logger, tracer, System.out)
      .runAsync(scheduler)
  }

  // Starts a run whose only entry is already being hashed by another compilation
  private def withWaitingRun(test: WaitingRun => Unit): Unit = {
    val dir = Files.createTempDirectory("classpath-hasher")
    val jar = dir.resolve("a.jar")
    try {
      Files.write(jar, "not really a jar".getBytes("UTF-8"))
      val owner = Promise[FileHash]()
      assertNull(ClasspathHasher.hashingPromises.putIfAbsent(jar, owner))
      val cancelCompilation = Promise[Unit]()
      val scheduler = TestScheduler()
      val logger = new RecordingLogger()
      val result = runHasher(jar, cancelCompilation, scheduler, logger)
      scheduler.tick()
      assertTrue("the run should wait for the owner", result.value.isEmpty)
      test(WaitingRun(jar, owner, cancelCompilation, scheduler, logger, result))
    } finally {
      // Don't leave the entry in the server-wide map when an assertion fails
      ClasspathHasher.hashingPromises.remove(jar)
      Paths.delete(AbsolutePath(dir))
    }
  }

  // Releases the entry like a finishing or cancelled owner: unregister, then complete
  private def release(run: WaitingRun, owner: Promise[FileHash], hash: FileHash): Unit = {
    ClasspathHasher.hashingPromises.remove(run.jar, owner)
    owner.success(hash)
  }

  // Cancels the owner after another compilation has acquired the entry
  private def cancelOwnerAfterHandOver(run: WaitingRun): Promise[FileHash] = {
    val nextOwner = Promise[FileHash]()
    ClasspathHasher.hashingPromises.remove(run.jar, run.owner)
    assertNull(ClasspathHasher.hashingPromises.putIfAbsent(run.jar, nextOwner))
    run.owner.success(BloopStamps.cancelledHash(run.jar))
    run.scheduler.tick()
    assertTrue(s"the run should wait for the next owner: ${run.describe}", run.result.value.isEmpty)
    nextOwner
  }

  private def contentHash(jar: Path): FileHash = FileHash.of(jar, ByteHasher.hashFile(jar.toFile))

  @Test
  def waiterUsesTheOwnersHash(): Unit = withWaitingRun { run =>
    val ownerHash = FileHash.of(run.jar, 42)
    release(run, run.owner, ownerHash)
    run.scheduler.tick()
    assertEquals(run.describe, Some(Success(Right(Vector(ownerHash)))), run.result.value)
    assertFalse(run.describe, run.cancelCompilation.isCompleted)
  }

  @Test
  def waiterRehashesWhenTheOwnerIsCancelled(): Unit = withWaitingRun { run =>
    release(run, run.owner, BloopStamps.cancelledHash(run.jar))
    run.scheduler.tick()
    val expected = Some(Success(Right(Vector(contentHash(run.jar)))))
    assertEquals(run.describe, expected, run.result.value)
    assertFalse(run.describe, run.cancelCompilation.isCompleted)
    assertNull(run.describe, ClasspathHasher.hashingPromises.get(run.jar))
    assertTrue(run.describe, run.logger.debugs.exists(_.contains("restarting")))
  }

  @Test
  def laterRunIsNotBlockedAfterARestart(): Unit = withWaitingRun { run =>
    release(run, run.owner, BloopStamps.cancelledHash(run.jar))
    run.scheduler.tick()
    val later = runHasher(run.jar, Promise[Unit](), run.scheduler, new RecordingLogger())
    run.scheduler.tick()
    val expected = Some(Success(Right(Vector(contentHash(run.jar)))))
    assertEquals(s"first run: ${run.describe}", expected, later.value)
  }

  @Test
  def cancelledWaiterReportsCancellation(): Unit = withWaitingRun { run =>
    // As if another project of the same request had been cancelled
    run.cancelCompilation.success(())
    release(run, run.owner, BloopStamps.cancelledHash(run.jar))
    run.scheduler.tick()
    assertEquals(run.describe, Some(Success(Left(()))), run.result.value)
    assertNull(run.describe, ClasspathHasher.hashingPromises.get(run.jar))
  }

  @Test
  def restartRehashesAgainWhenTheNextOwnerIsCancelled(): Unit = withWaitingRun { run =>
    val nextOwner = cancelOwnerAfterHandOver(run)
    release(run, nextOwner, BloopStamps.cancelledHash(run.jar))
    run.scheduler.tick()
    val expected = Some(Success(Right(Vector(contentHash(run.jar)))))
    assertEquals(run.describe, expected, run.result.value)
    assertFalse(run.describe, run.cancelCompilation.isCompleted)
    assertNull(run.describe, ClasspathHasher.hashingPromises.get(run.jar))
  }

  @Test
  def restartReportsCancellationWhenTheWaiterIsCancelled(): Unit = withWaitingRun { run =>
    val nextOwner = cancelOwnerAfterHandOver(run)
    run.cancelCompilation.success(())
    release(run, nextOwner, BloopStamps.cancelledHash(run.jar))
    run.scheduler.tick()
    assertEquals(run.describe, Some(Success(Left(()))), run.result.value)
    assertNull(run.describe, ClasspathHasher.hashingPromises.get(run.jar))
  }
}

object ClasspathHasherCancellationSpec {
  final case class WaitingRun(
      jar: Path,
      owner: Promise[FileHash],
      cancelCompilation: Promise[Unit],
      scheduler: TestScheduler,
      logger: RecordingLogger,
      result: Future[Either[Unit, Vector[FileHash]]]
  ) {
    def describe: String = {
      val leftover = Option(ClasspathHasher.hashingPromises.get(jar))
        .map(p => s"promise(completed = ${p.isCompleted})")
      s"result: ${result.value}, cancelled: ${cancelCompilation.isCompleted}, " +
        s"leftover: $leftover, debugs: ${logger.debugs}"
    }
  }
}
