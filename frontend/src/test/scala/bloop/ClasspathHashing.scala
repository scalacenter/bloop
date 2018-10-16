package bloop

import java.util.concurrent.TimeUnit

import bloop.io.Timer
import bloop.logging.RecordingLogger
import bloop.tasks.TestUtil
import org.junit.Test
import org.junit.experimental.categories.Category
import sbt.internal.inc.bloop.ClasspathHashing

import scala.concurrent.duration.{Duration, FiniteDuration}

@Category(Array(classOf[bloop.FastTests]))
class ClasspathHashing {
  @Test
  def detectsMacrosInClasspath(): Unit = {
    val logger = new RecordingLogger()
    val jars = DependencyResolution
      .resolve("ch.epfl.scala", "zinc_2.12", "1.2.1+97-636ca091", logger)
      .filter(_.syntax.endsWith(".jar"))

    Timer.timed(logger) {
      val duration = FiniteDuration(7, TimeUnit.SECONDS)
      TestUtil.await(duration) {
        ClasspathHashing.containsMacroDefinition(jars.map(_.toFile).toSeq).map { jarsCount =>
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
        ClasspathHashing.containsMacroDefinition(jars.map(_.toFile).toSeq).map { jarsCount =>
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
