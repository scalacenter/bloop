package bloop

import java.nio.file.Files
import bloop.logging.RecordingLogger
import org.junit.Assert._
import org.junit.Test

class DependencyResolutionSpec {
  @Test
  def fallbackDownloadShouldDownloadSemanticdb(): Unit = {
    val logger = new RecordingLogger()
    val dep = coursierapi.Dependency.of("org.scalameta", "semanticdb-scalac_2.12.20", "4.13.5")
    val jars = DependencyResolution.fallbackDownload(dep, logger, resolveSources = false)
    assertTrue("No jars were downloaded by fallbackDownload", jars.nonEmpty)
    jars.foreach { jar =>
      assertTrue(s"Downloaded jar does not exist: $jar", Files.exists(jar))
    }
  }
  @Test
  def fallbackDownloadShouldDownloadSemanticdbWithSources(): Unit = {
    val logger = new RecordingLogger()
    val dep = coursierapi.Dependency.of("org.scalameta", "semanticdb-scalac_2.12.20", "4.13.5")
    val jars = DependencyResolution.fallbackDownload(dep, logger, resolveSources = true)
    assertTrue("No jars were downloaded by fallbackDownload", jars.nonEmpty)
    jars.foreach { jar =>
      assertTrue(s"Downloaded jar does not exist: $jar", Files.exists(jar))
      assertTrue(
        s"Downloaded jar does not contain sources: $jar",
        jar.getFileName.toString.endsWith("sources.jar")
      )
    }
  }
}
