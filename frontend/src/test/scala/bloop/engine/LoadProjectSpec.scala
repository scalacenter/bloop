package bloop.engine

import java.util.concurrent.TimeUnit

import bloop.Project
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
}
