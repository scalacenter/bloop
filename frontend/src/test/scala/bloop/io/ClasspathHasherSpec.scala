package bloop.io

import java.util.concurrent.TimeUnit

import bloop.logging.RecordingLogger
import bloop.util.TestUtil
import bloop.tracing.BraveTracer
import bloop.DependencyResolution

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Await
import scala.concurrent.Promise

import monix.eval.Task

import sbt.internal.inc.bloop.internal.BloopStamps

object ClasspathHasherSpec extends bloop.testing.BaseSuite {
  ignore("cancellation works OK") {
    import bloop.engine.ExecutionContext.ioScheduler
    val logger = new RecordingLogger()
    val cancelPromise = Promise[Unit]()
    val cancelPromise2 = Promise[Unit]()
    val tracer = BraveTracer("cancels-correctly-test")
    val jars =
      Array(
        DependencyResolution.Artifact("org.apache.spark", "spark-core_2.11", "2.4.4"),
        DependencyResolution.Artifact("org.apache.hadoop", "hadoop-main", "3.2.1"),
        DependencyResolution.Artifact("io.monix", "monix_2.12", "3.0.0")
      ).flatMap(
        a =>
          // Force independent resolution for every artifact
          DependencyResolution.resolve(List(a), logger)
      )
    val hashClasspathTask =
      ClasspathHasher.hash(jars, 2, cancelPromise, ioScheduler, logger, tracer, System.out)
    val competingHashClasspathTask =
      ClasspathHasher.hash(jars, 2, cancelPromise2, ioScheduler, logger, tracer, System.out)
    val running = hashClasspathTask.runAsync(ioScheduler)

    Thread.sleep(10)
    val running2 = competingHashClasspathTask.runAsync(ioScheduler)

    Thread.sleep(5)
    running.cancel()

    val result = Await.result(running, FiniteDuration(20, "s"))
    TestUtil.await(FiniteDuration(1, "s"), ioScheduler)(Task.fromFuture(cancelPromise.future))
    assert(!cancelPromise2.isCompleted, result.isLeft)

    // Cancelling the first result doesn't affect the results of the second
    val competingResult = Await.result(running2, FiniteDuration(20, "s"))
    assert(
      competingResult.isRight,
      competingResult.forall(s => s != BloopStamps.cancelledHash),
      !cancelPromise2.isCompleted
    )
  }

  ignore("detect macros in classpath") {
    val logger = new RecordingLogger()
    import bloop.engine.ExecutionContext.ioScheduler
    val jars = DependencyResolution
      .resolve(
        List(DependencyResolution.Artifact("ch.epfl.scala", "zinc_2.12", "1.2.1+97-636ca091")),
        logger
      )
      .filter(_.syntax.endsWith(".jar"))

    Timer.timed(logger) {
      val duration = FiniteDuration(7, TimeUnit.SECONDS)
      TestUtil.await(duration) {
        ClasspathHasher.containsMacroDefinition(jars.map(_.toFile).toSeq).map { jarsCount =>
          jarsCount.foreach {
            case (jar, detected) =>
              if (detected)
                println(s"Detect macros in jar ${jar.getName}")
          }
        }
      }
    }

    Timer.timed(logger) {
      val duration = FiniteDuration(7, TimeUnit.SECONDS)
      TestUtil.await(duration) {
        ClasspathHasher.containsMacroDefinition(jars.map(_.toFile).toSeq).map { jarsCount =>
          jarsCount.foreach {
            case (jar, detected) =>
              if (detected)
                println(s"Detect macros in jar ${jar.getName}")
          }
        }
      }
    }

    logger.dump()
  }
}
