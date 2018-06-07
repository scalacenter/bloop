package bloop.engine

import java.util.concurrent.TimeUnit

import bloop.Project
import bloop.config.Config
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.tasks.TestUtil
import org.junit.Test

import scala.concurrent.duration.FiniteDuration

class LoadProjectSpec {
  @Test def LoadLichessGraph(): Unit = {
    val logger = new RecordingLogger()
    val configDir = AbsolutePath {
      TestUtil.testProjectsIndex
        .filter(_._1.contains("lichess"))
        .map(_._2)
        .headOption
        .getOrElse(sys.error("The lichess project doesn't exist in the integrations index!"))
    }

    val t = Project
      .lazyLoadFromDir(configDir, logger)
      .map(ps => Dag.fromMap(ps.map(p => p.name -> p).toMap))
    // Fail the test if this takes longer than 7 seconds -- In linux it's ~3.2s
    try TestUtil.await(FiniteDuration(7, TimeUnit.SECONDS))(t)
    catch { case t: Throwable => logger.dump(); throw t }
    ()
  }

  @Test def loadJavaProject(): Unit = {
    // Make sure that when no scala setup is configured the project load succeeds
    val logger = new RecordingLogger()
    val config0 = Config.File.dummyForTests
    val project = config0.project
    val emptyScala = Config.Scala("", "", "", Array(), Array())
    val configWithNoScala = config0.copy(config0.version, project.copy(scala = emptyScala))
    val inferredInstance = Project.fromConfig(configWithNoScala, logger).scalaInstance
    assert(inferredInstance.version.nonEmpty)
  }
}
