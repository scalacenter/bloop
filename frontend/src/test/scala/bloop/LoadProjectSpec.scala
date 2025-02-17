package bloop

import bloop.config.Config
import bloop.data.Platform
import bloop.data.Project
import bloop.engine.tasks.RunMode
import bloop.exec.JvmProcessForker
import bloop.io.AbsolutePath
import bloop.logging.Logger
import bloop.logging.RecordingLogger
import bloop.testing.BloopHelpers
import bloop.testing.TestInternals
import bloop.util.TestProject
import bloop.util.TestUtil

import org.junit.Test

class LoadProjectSpec extends BloopHelpers {
  @Test def LoadJavaProject(): Unit = {
    // Make sure that when no scala setup is configured the project load succeeds (and no scala instance is defined)
    val logger = new RecordingLogger()
    val config0 = Config.File.dummyForTests("JVM")
    val project = config0.project
    val configWithNoScala = config0.copy(config0.version, project.copy(scala = None))
    val origin = TestUtil.syntheticOriginFor(AbsolutePath.completelyUnsafe(""))
    val inferredInstance = Project.fromConfig(configWithNoScala, origin, logger).scalaInstance
    assert(inferredInstance.isEmpty)
  }

  @Test def CustomWorkingDirectory(): Unit = {
    val logger = new RecordingLogger()
    val dummyForTest = Config.File.dummyForTests("JVM")
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

  @Test def addTestNGFrameworkDependency(): Unit = {
    val logger = new RecordingLogger()
    val origin = TestUtil.syntheticOriginFor(AbsolutePath.completelyUnsafe(""))
    val project = Project.fromConfig(testNGProjectConfig(logger), origin, logger)

    val platform = project.platform.asInstanceOf[Platform.Jvm]
    val forker = JvmProcessForker(platform.config, platform.classpath.toArray, RunMode.Normal)
    val testLoader = forker.newClassLoader(Some(TestInternals.filteredLoader))
    val frameworks =
      project.testFrameworks.flatMap(f => TestInternals.loadFramework(testLoader, f.names, logger))
    TestUtil.assertNoDiff(frameworks.map(_.name()).mkString("\n"), "TestNG")
  }

  private def testNGProjectConfig(logger: Logger) = {
    val dummyForTest = Config.File.dummyForTests("JVM")
    val jvmPlatform =
      dummyForTest.project.platform.get.asInstanceOf[bloop.config.Config.Platform.Jvm]
    dummyForTest.copy(project =
      dummyForTest.project.copy(
        platform = Some(
          jvmPlatform.copy(classpath =
            jvmPlatform.classpath.map(_ ++ TestUtil.getTestNGDep(logger).map(_.underlying))
          )
        ),
        test = Some(Config.Test(List(Config.TestFramework.TestNG), Config.TestOptions(Nil, Nil)))
      )
    )
  }
}
