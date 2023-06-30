package bloop.testing

import java.nio.file.Files

import bloop.io.AbsolutePath
import bloop.io.Paths
import bloop.logging.RecordingLogger
import bloop.task.Task
import scala.concurrent.duration.Duration

class ProjectBaseSuite(buildName: String) extends BaseSuite {
  val workspace: AbsolutePath = AbsolutePath(Files.createTempDirectory(s"workspace-${buildName}"))
  val build: TestBuild = {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    loadBuildFromResources(buildName, workspace, logger)
  }

  def testProject(name: String, runOnlyOnJava8: Boolean)(
      fun: (TestBuild, RecordingLogger) => Any
  ): Unit = {
    val newLogger = new RecordingLogger(ansiCodesSupported = false)
    val newBuild = build.withLogger(newLogger)
    if (runOnlyOnJava8) testOnlyOnJava8(name)(fun(newBuild, newLogger))
    else test(name)(fun(newBuild, newLogger))
  }

  def testProjectTask(
      name: String,
      runOnlyOnJava8: Boolean,
      maxDuration: Duration = Duration("60s")
  )(
      fun: (TestBuild, RecordingLogger) => Task[Unit]
  ): Unit = {
    val newLogger = new RecordingLogger(ansiCodesSupported = false)
    val newBuild = build.withLogger(newLogger)
    if (runOnlyOnJava8) testOnlyOnJava8(name)(fun(newBuild, newLogger))
    else testTask(name, maxDuration)(fun(newBuild, newLogger))
  }

  override def test(name: String)(fun: => Any): Unit = {
    super.test(name)(fun)
  }

  override def afterAll(): Unit = {
    Paths.delete(workspace)
  }
}
