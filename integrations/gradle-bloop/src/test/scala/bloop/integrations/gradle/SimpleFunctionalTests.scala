package bloop.integrations.gradle

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.gradle.testkit.runner.{BuildResult, GradleRunner}
import org.gradle.testkit.runner.TaskOutcome._
import org.junit._
import org.junit.Assert._
import org.junit.rules.TemporaryFolder

import scala.collection.JavaConverters._

class SimpleFunctionalTests {
  private val gradleVersion: String = "4.8.1"

  private val testProjectDir_ = new TemporaryFolder()
  @Rule def testProjectDir: TemporaryFolder = testProjectDir_

  @Test def pluginCanBeApplied(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    writeBuildScript(buildFile,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'scala'
        |apply plugin: 'bloop'
      """.stripMargin)

    val result: BuildResult =
      GradleRunner.create()
        .withGradleVersion(gradleVersion)
        .withProjectDir(testProjectDir.getRoot)
        .withPluginClasspath(getClasspath.asJava)
        .withArguments("tasks")
        .build()

    assertEquals(SUCCESS, result.task(":tasks").getOutcome)
  }

  @Test def bloopInstallTaskAdded(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    writeBuildScript(buildFile,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'scala'
        |apply plugin: 'bloop'
      """.stripMargin)

    val result: BuildResult =
      GradleRunner.create()
        .withGradleVersion(gradleVersion)
        .withProjectDir(testProjectDir.getRoot)
        .withPluginClasspath(getClasspath.asJava)
        .withArguments("tasks", "--all")
        .build()

    assertTrue(result.getOutput.lines.contains("bloopInstall"))
  }

  private def getClasspath: List[File] =
    classOf[BloopPlugin].getClassLoader.asInstanceOf[URLClassLoader].getURLs.toList.map(url => new File(url.getFile))


  private def writeBuildScript(buildFile: File, contents: String): Unit = {
    Files.write(buildFile.toPath, contents.getBytes(StandardCharsets.UTF_8))
    ()
  }
}