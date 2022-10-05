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

object ClasspathHasherSpec extends bloop.testing.BaseSuite {
  val testTimeout = 10.seconds

  val monix = DependencyResolution.Artifact("io.monix", "monix_2.13", "3.4.0")
  val spark = DependencyResolution.Artifact("org.apache.spark", "spark-core_2.13", "3.3.0")
  val hadoop = DependencyResolution.Artifact("org.apache.hadoop", "hadoop-common", "3.3.4")

  // arbitrary jar was picked to be test if hashing returns consistent results across test runs
  val monixJar = "monix_2.13-3.4.0.jar"
  val expectedHash = 581779648

  testAsyncT("hash deps", testTimeout) {
    val cancelPromise = Promise[Unit]()
    val logger = new RecordingLogger()
    val tracer = BraveTracer("hashes-correctly", TraceProperties.default)
    val jars = resolveArtifacts(logger, monix)

    val hashClasspathTask =
      ClasspathHasher.hash(
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
      pprint.log(fileHashes)
      val emptyHashses = jars.map(path => BloopStamps.emptyHash(path.underlying))
      pprint.log(emptyHashses)
      assertEquals(
        obtained = fileHashes.forall(hash =>
          hash != BloopStamps.cancelledHash && hash != BloopStamps.emptyHash(hash.file)
        ),
        expected = true,
        hint = s"All hashes should be computed correctly, but found cancelled hash in $fileHashes"
      )
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
    val logger = new RecordingLogger()
    val tracer = BraveTracer("hashes-correctly", TraceProperties.default)
    val jars = resolveArtifacts(logger, monix)

    val hashClasspathTask =
      ClasspathHasher.hash(
        classpath = jars,
        parallelUnits = 2,
        cancelCompilation = cancelPromise,
        logger = logger,
        tracer = tracer,
        serverOut = System.out
      )

    for {
      _ <- hashClasspathTask
      cachedResult <- hashClasspathTask
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
        println(debugOutput)
        fail(
          s"Hashing should used cached results for when computing hashes, but $nonCached were computed"
        )
      }

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

      val hashClasspathTask =
        ClasspathHasher.hash(
          classpath = filesToHash,
          parallelUnits = 2,
          cancelCompilation = cancelPromise,
          logger = logger,
          tracer = tracer,
          serverOut = System.out
        )

      for {
        _ <- writeFileContent
        _ <- hashClasspathTask
        // write file's content once again to change lastModifiedTime
        _ <- writeFileContent
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

  testAsyncT("cancellation", 10.second) {
    import bloop.engine.ExecutionContext.ioScheduler

    val logger = new RecordingLogger()
    val cancelPromise = Promise[Unit]()
    val tracer = BraveTracer("cancels-correctly-test", TraceProperties.default)
    val jars = resolveArtifacts(logger, monix, spark, hadoop)

    val hashClasspathTask =
      ClasspathHasher.hash(
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

  private def resolveArtifacts(
      logger: Logger,
      deps: DependencyResolution.Artifact*
  ): Array[AbsolutePath] = {
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
