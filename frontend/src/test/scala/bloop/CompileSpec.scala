package bloop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import bloop.cli.{Commands, ExitStatus}
import bloop.config.Config
import bloop.logging.{Logger, RecordingLogger}
import bloop.util.{TestProject, TestUtil, BuildUtil}
import bloop.util.TestUtil.{
  RootProject,
  checkAfterCleanCompilation,
  ensureCompilationInAllTheBuild,
  getProject,
  hasPreviousResult,
  noPreviousAnalysis
}
import bloop.engine.tasks.Tasks
import bloop.engine.{Feedback, Run, State}
import bloop.io.AbsolutePath
import monix.execution.CancelableFuture
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.experimental.categories.Category
import org.junit.{Assert, Test}

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

@Category(Array(classOf[bloop.FastTests]))
class CompileSpec {
  object ArtificialSources {
    val `A.scala` = "package p0\nclass A"
    val `B.scala` = "package p1\nimport p0.A\nclass B extends A"
    val `B2.scala` = "package p1\ntrait B"
    val `C.scala` = "package p2\nimport p0.A\nimport p1.B\nobject C extends A with B"
    val `C2.scala` = "package p2\nimport p0.A\nobject C extends A"
    val `Dotty.scala` = "package p0\nobject Foo { val x: String | Int = 1 }"
  }

  def scalaInstance2124(logger: Logger): ScalaInstance = {
    ScalaInstance.resolve(
      "org.scala-lang",
      "scala-compiler",
      "2.12.4",
      logger
    )(bloop.engine.ExecutionContext.ioScheduler)
  }

  @Test
  def compileWithScala2124(): Unit = {
    val logger = new RecordingLogger
    val scalaInstance =
      ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.12.4", logger)(
        bloop.engine.ExecutionContext.ioScheduler
      )
    simpleProject(scalaInstance)
  }

  @Test
  def compileWithScala2123(): Unit = {
    val logger = new RecordingLogger
    val scalaInstance =
      ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.12.3", logger)(
        bloop.engine.ExecutionContext.ioScheduler
      )
    simpleProject(scalaInstance)
  }

  @Test
  def compileWithScala21111(): Unit = {
    val logger = new RecordingLogger
    val scalaInstance =
      ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.11.11", logger)(
        bloop.engine.ExecutionContext.ioScheduler
      )
    simpleProject(scalaInstance)
  }

  private def simpleProject(scalaInstance: ScalaInstance): Unit = {
    val dependencies = Map.empty[String, Set[String]]
    val structures = Map(RootProject -> Map("A.scala" -> "object A"))
    // Scala bug to report: removing `(_ => ())` fails to compile.
    checkAfterCleanCompilation(
      structures,
      dependencies,
      scalaInstance = scalaInstance,
      quiet = false
    )(_ => ())
  }

  /*
  @Test
  def compileWithDotty080RC1(): Unit = {
    val logger = new RecordingLogger()
    val scalaInstance =
      ScalaInstance.resolve("ch.epfl.lamp", "dotty-compiler_0.8", "0.8.0-RC1", logger)(
        bloop.engine.ExecutionContext.ioScheduler
      )
    val structures = Map(RootProject -> Map("Dotty.scala" -> ArtificialSources.`Dotty.scala`))
    checkAfterCleanCompilation(structures, Map.empty, scalaInstance = scalaInstance) { state =>
      ensureCompilationInAllTheBuild(state)
    }
  }
   */

  @Test
  def zipkin(): Unit = {
    import bloop.tracing.BraveTracer

    val tracer = BraveTracer("encode")
    Thread.sleep(2000)
    tracer.trace("previous children") { tracer =>
      Thread.sleep(1000)
      tracer.trace("inside children") { tracer =>
        Thread.sleep(1000)
      }
    }

    tracer.trace("next children") { tracer =>
      Thread.sleep(500)
    }
    Thread.sleep(2000)
    tracer.terminate()
  }
}
