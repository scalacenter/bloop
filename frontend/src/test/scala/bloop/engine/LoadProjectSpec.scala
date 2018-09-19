package bloop.engine

import java.util.concurrent.TimeUnit

import bloop.config.Config
import bloop.data.Project
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

    val t = BuildLoader
      .load(configDir, logger)
      .map(ps => Dag.fromMap(ps.map(p => p.name -> p).toMap))
    try TestUtil.await(FiniteDuration(5, TimeUnit.SECONDS))(t)
    catch { case t: Throwable => logger.dump(); throw t }
    ()
  }

  @Test def LoadJavaProject(): Unit = {
    // Make sure that when no scala setup is configured the project load succeeds (and the default instance is used)
    val logger = new RecordingLogger()
    val config0 = Config.File.dummyForTests
    val project = config0.project
    val configWithNoScala = config0.copy(config0.version, project.copy(scala = None))
    val origin = TestUtil.syntheticOriginFor(AbsolutePath.completelyUnsafe(""))
    val inferredInstance = Project.fromConfig(configWithNoScala, origin, logger).scalaInstance
    assert(inferredInstance.isDefined)
    assert(inferredInstance.get.version.nonEmpty)
  }
}
