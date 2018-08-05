package bloop.integrations.gradle

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import bloop.config.Config
import bloop.config.ConfigEncoderDecoders._
import io.circe._, io.circe.parser._

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
    writeBuildScript(
      buildFile,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'scala'
        |apply plugin: 'bloop'
      """.stripMargin
    )

    val result: BuildResult =
      GradleRunner
        .create()
        .withGradleVersion(gradleVersion)
        .withProjectDir(testProjectDir.getRoot)
        .withPluginClasspath(getClasspath.asJava)
        .withArguments("tasks")
        .build()

    assertEquals(SUCCESS, result.task(":tasks").getOutcome)
  }

  @Test def bloopInstallTaskAdded(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    writeBuildScript(
      buildFile,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'scala'
        |apply plugin: 'bloop'
      """.stripMargin
    )

    val result: BuildResult =
      GradleRunner
        .create()
        .withGradleVersion(gradleVersion)
        .withProjectDir(testProjectDir.getRoot)
        .withPluginClasspath(getClasspath.asJava)
        .withArguments("tasks", "--all")
        .build()

    assertTrue(result.getOutput.lines.contains("bloopInstall"))
  }

  @Test def worksWithScala211Project(): Unit = {
    worksWithGivenScalaVersion("2.11.12")
  }

  @Test def worksWithScala212Project(): Unit = {
    worksWithGivenScalaVersion("2.12.6")
  }

  @Test def encodingOptionGeneratedCorrectly(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    writeBuildScript(
      buildFile,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile group: 'org.scala-lang', name: 'scala-library', version: '2.12.6'
         |}
         |
         |tasks.withType(ScalaCompile) {
         |	scalaCompileOptions.additionalParameters = ["-deprecation", "-unchecked", "-encoding", "utf8"]
         |}
         |
      """.stripMargin
    )

    createHelloWorldScalaSource()

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopFile = new File(new File(testProjectDir.getRoot, ".bloop"), projectName + ".json")

    val resultConfig = readValidBloopConfig(bloopFile)

    assertArrayEquals(
      Array.apply[Object]("-deprecation", "-unchecked", "-encoding", "utf8"),
      resultConfig.project.`scala`.get.options.toArray[Object]
    )
  }

  @Test def generateConfigFileForJavaOnlyProjects(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    writeBuildScript(
      buildFile,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
      """.stripMargin
    )

    createHelloWorldJavaSource()

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopFile = new File(new File(testProjectDir.getRoot, ".bloop"), projectName + ".json")

    val resultConfig = readValidBloopConfig(bloopFile)
    assertTrue(!resultConfig.project.`scala`.isDefined)
  }

  private def worksWithGivenScalaVersion(version: String): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    writeBuildScript(
      buildFile,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile group: 'org.scala-lang', name: 'scala-library', version: "$version"
         |}
      """.stripMargin
    )

    createHelloWorldScalaSource()

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopFile = new File(new File(testProjectDir.getRoot, ".bloop"), projectName + ".json")

    val resultConfig = readValidBloopConfig(bloopFile)

    assertTrue(resultConfig.project.`scala`.isDefined)
    assertEquals(version, resultConfig.project.`scala`.get.version)
  }

  private def createHelloWorldJavaSource(): Unit = {
    val srcDir = testProjectDir.newFolder("src", "main", "java")
    val srcFile = new File(srcDir, "Hello.java")
    val src =
      """
        |public class java {
        |    public static void main(String[] args) {
        |        System.out.println("Hello World");
        |    }
        |}
      """.stripMargin
    Files.write(srcFile.toPath, src.getBytes(StandardCharsets.UTF_8))
    ()
  }

  private def createHelloWorldScalaSource(): Unit = {
    val srcDir = testProjectDir.newFolder("src", "main", "scala")
    val srcFile = new File(srcDir, "Hello.scala")
    val src =
      """
        |object Hello {
        |  def main(args: Array[String]): Unit = {
        |    println("Hello")
        |  }
        |}
      """.stripMargin
    Files.write(srcFile.toPath, src.getBytes(StandardCharsets.UTF_8))
    ()
  }

  private def readValidBloopConfig(file: File): Config.File = {
    assertTrue("The bloop project file exists", file.exists())
    parse(new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)) match {
      case Left(failure) =>
        throw new AssertionError(s"Failed to parse ${file.getAbsolutePath}: $failure")
      case Right(json) =>
        json.as[Config.File] match {
          case Left(failure) =>
            throw new AssertionError(s"Failed to decode ${file.getAbsolutePath}: $failure")
          case Right(result) =>
            result
        }
    }
  }

  private def getClasspath: List[File] = {
    classOf[BloopPlugin].getClassLoader
      .asInstanceOf[URLClassLoader]
      .getURLs
      .toList
      .map(url => new File(url.getFile))
  }

  private def writeBuildScript(buildFile: File, contents: String): Unit = {
    Files.write(buildFile.toPath, contents.getBytes(StandardCharsets.UTF_8))
    ()
  }
}
