package bloop.io

import scala.concurrent.Promise
import scala.concurrent.duration._

import bloop.DependencyResolution
import bloop.logging.Logger
import bloop.logging.RecordingLogger
import bloop.task.Task
import bloop.tracing.BraveTracer
import bloop.tracing.TraceProperties
import bloop.util.TestUtil

import sbt.internal.inc.bloop.internal.BloopStamps
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import sbt.io.IO

object ClasspathHasherSpec extends bloop.testing.BaseSuite {
  val testTimeout = 10.seconds

  val jsoniter = DependencyResolution.Artifact(
    "com.github.plokhotnyuk.jsoniter-scala",
    "jsoniter-scala-core_2.13",
    "2.17.5"
  )
  val monix = DependencyResolution.Artifact("io.monix", "monix_2.13", "3.4.0")
  val spark = DependencyResolution.Artifact("org.apache.spark", "spark-core_2.13", "3.3.0")
  val hadoop = DependencyResolution.Artifact("org.apache.hadoop", "hadoop-common", "3.3.4")

  // arbitrary jar was picked to be test if hashing returns consistent results across test runs
  val monixJar = "monix_2.13-3.4.0.jar"
  val expectedHash = 581779648

  def makeClasspathHasher: ClasspathHasher = new ClasspathHasher

  testAsyncT("hash deps", testTimeout) {
    val cancelPromise = Promise[Unit]()
    val logger = new RecordingLogger()
    val tracer = BraveTracer("hashes-correctly", TraceProperties.default)
    val jars = resolveArtifacts(monix)

    val hashClasspathTask =
      makeClasspathHasher.hash(
        classpath = jars,
        parallelUnits = 2,
        cancelCompilation = cancelPromise,
        logger = logger,
        tracer = tracer,
        serverOut = System.out
      )

    for {
      result <- hashClasspathTask
    } yield {
      val fileHashes = result.orFail(_ => "Obtained empty result from hashing")
      assertEquals(
        obtained = cancelPromise.isCompleted,
        expected = false,
        "Cancel promise shouldn't be completed if hashing wasn't cancelled"
      )
      assertEquals(
        obtained = fileHashes.forall(hash =>
          hash != BloopStamps.cancelledHash && hash != BloopStamps.emptyHash(hash.file)
        ),
        expected = true,
        hint = s"All hashes should be computed correctly, but found cancelled hash in $fileHashes"
      )

      val dependencies = jars.toVector
      val cacheMisses = dependencies.flatMap { path =>
        logger.debugs.find(entry =>
          entry.contains(path.toString) && entry.startsWith("Cache miss for")
        )
      }

      // check if all jars hit cache miss and were computed
      assertEquals(
        obtained = cacheMisses.size,
        expected = dependencies.size,
        hint = s"Not everyone entry missed the cache when hashing ${dependencies
            .mkString("\n")} (${fileHashes.mkString("\n")})"
      )

      // check if hash is computed in a stable way across test runs
      fileHashes.find(_.file.toString.contains(monixJar)) match {
        case None => fail(s"There is no $monixJar among hashed jars, although it should be")
        case Some(fileHash) =>
          assertEquals(
            obtained = fileHash.hash,
            expected = expectedHash
          )
      }
    }
  }

  testAsyncT("results are cached", testTimeout) {
    val cancelPromise = Promise[Unit]()
    val tracer = BraveTracer("hashes-correctly", TraceProperties.default)
    val jars = resolveArtifacts(monix)

    val classpathHasher = makeClasspathHasher

    def hashClasspathTask(logger: Logger) =
      classpathHasher.hash(
        classpath = jars,
        parallelUnits = 2,
        cancelCompilation = cancelPromise,
        logger = logger,
        tracer = tracer,
        serverOut = System.out
      )

    for {
      logger1 <- Task.now(new RecordingLogger())
      _ <- hashClasspathTask(logger1)
      logger = new RecordingLogger()
      cachedResult <- hashClasspathTask(logger)
    } yield {

      val fileHashes = cachedResult.orFail(_ => "Obtained empty result from hashing")
      assertEquals(
        obtained = fileHashes.forall(_ != BloopStamps.cancelledHash),
        expected = true,
        hint = s"All hashes should be computed correctly, but found cancelled hash in $fileHashes"
      )

      val debugOutput = logger.debugs.toSet

      val nonCached = jars.toList.filter { path =>
        !debugOutput.contains(s"Using cached hash for $path")
      }

      if (nonCached.nonEmpty) {
        fail(
          s"Hashing should used cached results for when computing hashes, but $nonCached were computed"
        )
      }
    }
  }

  testAsyncT("work is shared accros tasks", testTimeout) {
    import bloop.engine.ExecutionContext.ioScheduler

    val cancelPromise = Promise[Unit]()
    val tracer = BraveTracer("results are cached", TraceProperties.default)

    // use small library to not pollute test output
    val jars = resolveArtifacts(jsoniter)

    val classpathHasher = makeClasspathHasher

    def hashClasspathTask(logger: Logger) =
      classpathHasher.hash(
        classpath = jars,
        parallelUnits = 2,
        cancelCompilation = cancelPromise,
        logger = logger,
        tracer = tracer,
        serverOut = System.out
      )

    for {
      _ <- Task.now(hashClasspathTask(new RecordingLogger()).runAsync)
      logger2 = new RecordingLogger()
      cachedResult <- Task.fromFuture(hashClasspathTask(logger2).runAsync)
    } yield {

      val fileHashes = cachedResult.orFail(_ => "Obtained empty result from hashing")
      assertEquals(
        obtained = fileHashes.forall(_ != BloopStamps.cancelledHash),
        expected = true,
        hint = s"All hashes should be computed correctly, but found cancelled hash in $fileHashes"
      )

      val debugOutput = logger2.debugs.toSet

      val nonCached = jars.toList.filterNot { path =>
        debugOutput.contains(s"Wait for hashing of $path to complete")
      }

      if (nonCached.nonEmpty) {
        fail(
          s"Hashing should share workload when computing hashes at the same time, but $nonCached were computed"
        )
      }
    }
  }

  testAsyncT("hash is recalculated if file's metadata changed", testTimeout) {
    TestUtil.withinWorkspaceT { workspace =>
      val cancelPromise = Promise[Unit]()
      val logger = new RecordingLogger()
      val tracer = BraveTracer("hashes-correctly", TraceProperties.default)

      val file = os.Path(workspace.underlying) / "a.txt"

      val writeFileContent = Task {
        os.write.over(
          target = file,
          data = "This is very huge Scala jar :)"
        )
      }

      val filesToHash = Array(AbsolutePath(file.toNIO))

      val classpathHasher = makeClasspathHasher

      val hashClasspathTask =
        classpathHasher.hash(
          classpath = filesToHash,
          parallelUnits = 2,
          cancelCompilation = cancelPromise,
          logger = logger,
          tracer = tracer,
          serverOut = System.out
        )

      val readAttributes = Task(Files.readAttributes(file.toNIO, classOf[BasicFileAttributes]))
      val lastModifiedTime = Task(IO.getModifiedTimeOrZero(file.toIO))
      for {
        _ <- writeFileContent
        attributes <- readAttributes
        time <- lastModifiedTime
        _ <- hashClasspathTask
        // make sure that at least one milli has ticked
        _ <- Task.sleep(1.milli)
        // write file's content once again to change lastModifiedTime
        _ <- writeFileContent
        newAttributes <- readAttributes
        newTime <- lastModifiedTime
        _ = assertNotEquals(
          attributes.lastModifiedTime().toMillis,
          newAttributes.lastModifiedTime().toMillis,
          "Modified change should change"
        )
        _ = assertNotEquals(
          time,
          newTime,
          "Modified change should change"
        )
        cachedResult <- hashClasspathTask
      } yield {
        val fileHashes = cachedResult.orFail(_ => "Obtained empty result from hashing")
        assertEquals(
          obtained = fileHashes.forall(_ != BloopStamps.cancelledHash),
          expected = true,
          hint = s"All hashes should be computed correctly, but found cancelled hash in $fileHashes"
        )

        val debugOutput = logger.debugs

        val cached = filesToHash.toList.filterNot { path =>
          debugOutput.exists(_.contains(s"$path has different metadata"))
        }

        cached match {
          case Nil => ()
          case nonempty =>
            fail(
              s"Hashing should recompute hashes if file's metadata has changed, but $nonempty were taken from cache"
            )
        }
      }
    }
  }

  testAsyncT("cancellation of single task", testTimeout) {
    import bloop.engine.ExecutionContext.ioScheduler

    val logger = new RecordingLogger()
    val cancelPromise = Promise[Unit]()
    val tracer = BraveTracer("cancel-single-task", TraceProperties.default)

    // use big libraries with a lot of dependencies, some of them have common deps which will
    // result in situation where dep A is being computed by task1 and at the same time by task2
    // in such case task2 is waiting for task1 to be be finished
    val jars = resolveArtifacts(monix, spark, hadoop)

    val classpathHasher = makeClasspathHasher

    val hashClasspathTask =
      classpathHasher.hash(
        jars,
        2,
        cancelPromise,
        logger,
        tracer,
        System.out
      )

    // start hashing, wait small amount of time and then cancel task
    val cancelRunningTask = Task.defer {
      for {
        running <- Task(hashClasspathTask.runAsync)
        _ <- Task.sleep(10.millis)
        _ <- Task(running.cancel())
        result <- Task.fromFuture(running)
      } yield result
    }

    for {
      result <- cancelRunningTask
    } yield {
      assertEquals(
        obtained = cancelPromise.isCompleted,
        expected = true,
        hint = "Cancelation promise should be completed"
      )

      result match {
        case Left(_) => ()
        case Right(hashes) => fail(s"Cancelled hash task should return empty result, got $hashes")
      }
    }
  }

  testAsyncT("cancel one task, other one should work just fine", testTimeout) {
    import bloop.engine.ExecutionContext.ioScheduler

    val logger1 = new RecordingLogger()
    val cancelPromise1 = Promise[Unit]()

    val logger2 = new RecordingLogger()
    val cancelPromise2 = Promise[Unit]()

    val tracer = BraveTracer("cancel-single-task", TraceProperties.default)

    // use big libraries with a lot of dependencies, some of them have common deps which will
    // result in situation where dep A is being computed by task1 and at the same time by task2
    // in such case task2 is waiting for task1 to be be finished
    val jars = resolveArtifacts(monix, spark, hadoop)

    val classpathHasher = makeClasspathHasher

    def hashClasspathTask(logger: Logger, cancelPromise: Promise[Unit]) =
      classpathHasher.hash(
        jars,
        2,
        cancelPromise,
        logger,
        tracer,
        System.out
      )

    // start hashing, wait small amount of time and then cancel task
    val startAndCancelTask = Task.defer {
      for {
        running <-
          Task(hashClasspathTask(logger1, cancelPromise1).runAsync)
        _ <- Task.sleep(20.millis)
        _ <- Task(running.cancel())
      } yield ()
    }

    for {
      _ <- startAndCancelTask
      result <- hashClasspathTask(logger2, cancelPromise2)
    } yield {
      assertEquals(
        obtained = cancelPromise1.isCompleted,
        expected = true,
        hint = "Cancelation promise of task1 should be completed"
      )

      assertEquals(
        obtained = cancelPromise2.isCompleted,
        expected = false,
        hint = "Cancelation promise of task2 should not be completed"
      )

      assertEquals(
        obtained = result.isRight,
        expected = true,
        hint = "Task2 should return valid result"
      )

      assertEquals(
        obtained = logger2.warnings.exists(_.startsWith("Unexpected hash computation of")),
        expected = true,
        hint = "Task2 should recover from task1 cancellation"
      )
    }
  }

  private def resolveArtifacts(
      deps: DependencyResolution.Artifact*
  ): Array[AbsolutePath] = {
    val logger = new RecordingLogger()
    deps.toArray.flatMap(a =>
      // Force independent resolution for every artifact
      DependencyResolution.resolve(List(a), logger)
    )
  }

  private implicit class EitherSyntax[A, B](private val either: Either[A, B]) extends AnyVal {
    def orFail(msg: A => String): B = either match {
      case Left(value) => fail(msg(value))
      case Right(value) => value
    }
  }
}
