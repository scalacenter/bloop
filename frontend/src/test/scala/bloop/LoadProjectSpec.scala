package bloop

import bloop.config.Config
import bloop.data.Project
import bloop.data.Platform
import bloop.data.JdkConfig
import bloop.io.Paths
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.TestUtil
import org.junit.Test
import bloop.util.TestProject
import bloop.testing.BloopHelpers

class LoadProjectSpec extends BloopHelpers {
  @Test def LoadJavaProject(): Unit = {
    // Make sure that when no scala setup is configured the project load succeeds (and no scala instance is defined)
    val logger = new RecordingLogger()
    val config0 = Config.File.dummyForTests
    val project = config0.project
    val configWithNoScala = config0.copy(config0.version, project.copy(scala = None))
    val origin = TestUtil.syntheticOriginFor(AbsolutePath.completelyUnsafe(""))
    val inferredInstance = Project.fromConfig(configWithNoScala, origin, logger).scalaInstance
    assert(inferredInstance.isEmpty)
  }

  @Test def CustomWorkingDirectory(): Unit = {
    val logger = new RecordingLogger()
    val dummyForTest = Config.File.dummyForTests
    val origin = TestUtil.syntheticOriginFor(AbsolutePath.completelyUnsafe(""))
    val project = Project.fromConfig(dummyForTest, origin, logger)
    assert(
      project.baseDirectory == project.workingDirectory,
      s"${project.baseDirectory} != ${project.workingDirectory}"
    )
    val platform = project.platform.asInstanceOf[Platform.Jvm]
    val userdir = AbsolutePath("foobar")
    val customProject = project.copy(
      platform =
        platform.copy(config = platform.config.copy(javaOptions = Array(s"-Duser.dir=$userdir")))
    )
    assert(
      customProject.workingDirectory == userdir,
      s"${customProject.workingDirectory} != ${userdir}"
    )
  }

  @Test(expected = classOf[Project.ProjectReadException])
  def detectMissingCompiler(): Unit = {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger()
      val `A` = TestProject(workspace, "A", Nil).rewriteProject { scala =>
        val jarsWithNoCompiler = scala.jars.filterNot(_.toString.contains("compiler"))
        scala.copy(jars = jarsWithNoCompiler)
      }

      // Should throw project read exception
      val _ = loadState(workspace, List(`A`), logger)
      ()
    }
  }
}
