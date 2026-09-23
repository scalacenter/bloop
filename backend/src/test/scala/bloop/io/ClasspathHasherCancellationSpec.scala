package bloop.io

import java.nio.file.Files

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Success

import bloop.logging.RecordingLogger
import bloop.tracing.BraveTracer
import bloop.tracing.TraceProperties

import monix.execution.schedulers.TestScheduler
import org.junit.Assert._
import org.junit.Test
import xsbti.compile.FileHash

class ClasspathHasherCancellationSpec {
  private def hash(
      classpath: Array[AbsolutePath],
      cancelCompilation: Promise[Unit],
      scheduler: TestScheduler
  ): Future[Either[Unit, Vector[FileHash]]] = {
    val tracer = BraveTracer("classpath-hasher-test", TraceProperties.default)
    ClasspathHasher
      .hash(classpath, 2, cancelCompilation, scheduler, new RecordingLogger(), tracer, System.out)
      .runAsync(scheduler)
  }

  private def withClasspath(test: Array[AbsolutePath] => Unit): Unit = {
    val dir = Files.createTempDirectory("classpath-hasher")
    try {
      val jars = (1 to 3).map { i =>
        AbsolutePath(Files.write(dir.resolve(s"$i.jar"), s"jar $i".getBytes("UTF-8")))
      }
      test(jars.toArray)
    } finally Paths.delete(AbsolutePath(dir))
  }

  private def contentHashes(classpath: Array[AbsolutePath]): Vector[FileHash] =
    classpath.toVector.map(jar => FileHash.of(jar.underlying, ByteHasher.hashFile(jar.toFile)))

  @Test
  def cancelledCompilationDoesNotCancelAnother(): Unit = withClasspath { classpath =>
    // Each compilation runs on its own scheduler, so the test decides how they interleave
    val cancelledScheduler = TestScheduler()
    val scheduler = TestScheduler()
    val cancelled = hash(classpath, Promise.successful(()), cancelledScheduler)
    // Acquires the entries, whose hashing happens in later steps
    cancelledScheduler.tickOne()

    val cancelCompilation = Promise[Unit]()
    val waiting = hash(classpath, cancelCompilation, scheduler)
    scheduler.tick()
    assertTrue("should wait for the entries of the other compilation", waiting.value.isEmpty)

    cancelledScheduler.tick()
    scheduler.tick()
    assertEquals(Some(Success(Left(()))), cancelled.value)
    assertEquals(Some(Success(Right(contentHashes(classpath)))), waiting.value)
    assertFalse(cancelCompilation.isCompleted)

    // Nothing is left behind that would block a later compilation
    val later = hash(classpath, Promise[Unit](), scheduler)
    scheduler.tick()
    assertEquals(Some(Success(Right(contentHashes(classpath)))), later.value)
  }
}
