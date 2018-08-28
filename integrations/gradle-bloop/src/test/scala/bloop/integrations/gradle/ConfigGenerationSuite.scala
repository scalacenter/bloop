package bloop.integrations.gradle

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import bloop.Project
import bloop.cli.Commands
import bloop.config.Config
import bloop.config.ConfigEncoderDecoders._
import bloop.engine.{Build, Run, State}
import bloop.io.AbsolutePath
import bloop.logging.BloopLogger
import bloop.tasks.{CompilationHelpers, TestUtil}
import io.circe.parser._
import org.gradle.testkit.runner.{BuildResult, GradleRunner}
import org.gradle.testkit.runner.TaskOutcome._
import org.junit._
import org.junit.Assert._
import org.junit.rules.TemporaryFolder

import scala.collection.JavaConverters._

class ConfigGenerationSuite {
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

  @Test def worksWithSeveralScalaProjects(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    val buildDirB = testProjectDir.newFolder("b")
    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")

    writeBuildScript(
      buildFileA,
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
         |  compile 'org.scala-lang:scala-library:2.12.6'
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileB,
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
         |  compile 'org.typelevel:cats-core_2.12:1.2.0'
         |  compile project(':a')
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'scala-multi-projects'
        |include 'a'
        |include 'b'
      """.stripMargin
    )

    createHelloWorldScalaSource(buildDirA, "package x { trait A }")
    createHelloWorldScalaSource(buildDirB, "package y { trait B extends x.A }")

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopNone = new File(bloopDir, s"${projectName}.json")
    val bloopA = new File(bloopDir, "a.json")
    val bloopB = new File(bloopDir, "b.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopBTest = new File(bloopDir, "b-test.json")

    assertFalse(bloopNone.exists())
    val configA = readValidBloopConfig(bloopA)
    val configB = readValidBloopConfig(bloopB)
    val configATest = readValidBloopConfig(bloopATest)
    val configBTest = readValidBloopConfig(bloopBTest)
    assertTrue(configA.project.`scala`.exists(_.version == "2.12.6"))
    assertTrue(configB.project.`scala`.exists(_.version == "2.12.6"))
    assertTrue(configB.project.dependencies == List("a"))
    assertTrue(configATest.project.dependencies == List("a"))
    assertTrue(configBTest.project.dependencies.sorted == List("a", "b"))

    def hasClasspathEntryName(config: Config.File, entryName: String): Boolean =
      config.project.classpath.exists(_.toString.contains(entryName))

    assertTrue(hasClasspathEntryName(configA, "scala-library"))
    assertTrue(hasClasspathEntryName(configB, "scala-library"))
    assertTrue(hasClasspathEntryName(configATest, "scala-library"))
    assertTrue(hasClasspathEntryName(configBTest, "scala-library"))
    assertTrue(hasClasspathEntryName(configB, "cats-core"))
    assertTrue(hasClasspathEntryName(configB, "/a/build/classes/bloop/main"))
    assertTrue(hasClasspathEntryName(configBTest, "cats-core"))
    assertTrue(hasClasspathEntryName(configBTest, "/a/build/classes/bloop/main"))
    assertTrue(hasClasspathEntryName(configBTest, "/b/build/classes/bloop/main"))

    assertTrue(compileBloopProject("b", bloopDir).status.isOk)
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

    createHelloWorldScalaSource(testProjectDir.getRoot)

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
      Array.apply[Object](
        "-deprecation",
        "-encoding", "utf8",
        "-unchecked"),
      resultConfig.project.`scala`.get.options.toArray[Object]
    )
  }

  @Test def flagsWithArgsGeneratedCorrectly(): Unit = {
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
         |	scalaCompileOptions.additionalParameters = [
         |    "-deprecation",
         |    "-Yjar-compression-level", "0",
         |    "-Ybackend-parallelism", "8",
         |    "-unchecked",
         |    "-encoding", "utf8"]
         |}
         |
      """.stripMargin
    )

    createHelloWorldScalaSource(testProjectDir.getRoot)

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
      Array.apply[Object](
        "-Ybackend-parallelism", "8",
        "-Yjar-compression-level", "0",
        "-deprecation",
        "-encoding", "utf8",
        "-unchecked"),
      resultConfig.project.`scala`.get.options.toArray[Object]
    )
  }

  @Test def generateConfigFileForNonJavaNonScalaProjects(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    writeBuildScript(
      buildFile,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
      """.stripMargin
    )

    createHelloWorldJavaSource()

    val result = GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    assertTrue(
      result.getOutput.contains("Ignoring 'bloopInstall' on non-Scala and non-Java project"))
    val projectName = testProjectDir.getRoot.getName
    val bloopFile = new File(new File(testProjectDir.getRoot, ".bloop"), projectName + ".json")
    assertTrue(!bloopFile.exists())
  }

  @Test def generateConfigFileForNonJavaNonScalaProjectDependencies(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    val buildDirB = testProjectDir.newFolder("b")
    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")

    writeBuildScript(
      buildFileA,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |configurations {
         |  foo
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileB,
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
         |  compile 'org.typelevel:cats-core_2.12:1.2.0'
         |  compile(project(path: ':a',  configuration: 'foo'))
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'scala-multi-projects-nonjava-dep'
        |include ':a'
        |include ':b'
      """.stripMargin
    )

    createHelloWorldScalaSource(buildDirB, "package y { trait B }")

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopNone = new File(bloopDir, s"$projectName.json")
    val bloopA = new File(bloopDir, "a.json")
    val bloopB = new File(bloopDir, "b.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopBTest = new File(bloopDir, "b-test.json")

    assertFalse(bloopNone.exists())
    assertFalse(bloopA.exists())
    assertFalse(bloopATest.exists())
    val configB = readValidBloopConfig(bloopB)
    val configBTest = readValidBloopConfig(bloopBTest)
    assertTrue(configB.project.`scala`.exists(_.version == "2.12.6"))
    assertEquals(Nil, configB.project.dependencies)
    assertEquals(List("b"), configBTest.project.dependencies)

    def hasClasspathEntryName(config: Config.File, entryName: String): Boolean =
      config.project.classpath.exists(_.toString.contains(entryName))

    assertTrue(hasClasspathEntryName(configB, "scala-library"))
    assertTrue(hasClasspathEntryName(configBTest, "scala-library"))
    assertTrue(hasClasspathEntryName(configB, "cats-core"))
    assertTrue(hasClasspathEntryName(configBTest, "cats-core"))

    assertTrue(compileBloopProject("b", bloopDir).status.isOk)
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
         |apply plugin: 'java'
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
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val projectFile = new File(bloopDir, s"${projectName}.json")
    val projectTestFile = new File(bloopDir, s"${projectName}-test.json")

    val projectConfig = readValidBloopConfig(projectFile)
    assertFalse(projectConfig.project.`scala`.isDefined)
    assertTrue(projectConfig.project.dependencies.isEmpty)
    assertTrue(projectConfig.project.classpath.isEmpty)

    val projectTestConfig = readValidBloopConfig(projectTestFile)
    assertFalse(projectConfig.project.`scala`.isDefined)
    assertTrue(projectTestConfig.project.dependencies == List(projectName))
    assertTrue(compileBloopProject(s"${projectName}-test", bloopDir).status.isOk)
  }

  def loadBloopState(configDir: File): State = {
    val logger = BloopLogger.default(configDir.toString())
    assert(Files.exists(configDir.toPath), "Does not exist: " + configDir)
    val configDirectory = AbsolutePath(configDir)
    val loadedProjects = Project.eagerLoadFromDir(configDirectory, logger)
    val build = Build(configDirectory, loadedProjects)
    State.forTests(build, CompilationHelpers.getCompilerCache(logger), logger)
  }

  def compileBloopProject(projectName: String, bloopDir: File, verbose: Boolean = false): State = {
    val state0 = loadBloopState(bloopDir)
    val state = if (verbose) state0.copy(logger = state0.logger.asVerbose) else state0
    val action = Run(Commands.Compile(project = projectName))
    TestUtil.blockingExecute(action, state0)
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

    createHelloWorldScalaSource(testProjectDir.getRoot)

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val projectFile = new File(bloopDir, s"${projectName}.json")
    val projectTestFile = new File(bloopDir, s"${projectName}-test.json")
    val configFile = readValidBloopConfig(projectFile)
    val configTestFile = readValidBloopConfig(projectTestFile)

    assertTrue(configFile.project.`scala`.isDefined)
    assertEquals(version, configFile.project.`scala`.get.version)
    assertTrue(configFile.project.classpath.nonEmpty)
    assertTrue(configFile.project.dependencies.isEmpty)

    assertTrue(configTestFile.project.dependencies == List(projectName))
    assertTrue(compileBloopProject(s"${projectName}-test", bloopDir).status.isOk)
  }

  private def createHelloWorldJavaSource(): Unit = {
    val srcDir = testProjectDir.newFolder("src", "main", "java")
    val srcFile = new File(srcDir, "Hello.java")
    val src =
      """
        |public class Hello {
        |    public static void main(String[] args) {
        |        System.out.println("Hello World");
        |    }
        |}
      """.stripMargin
    Files.write(srcFile.toPath, src.getBytes(StandardCharsets.UTF_8))
    ()
  }

  private final val HelloWorldSource: String = {
    """
      |object Hello {
      |  def main(args: Array[String]): Unit = {
      |    println("Hello")
      |  }
      |}
    """.stripMargin
  }

  private def createHelloWorldScalaSource(projectDir: File, source: String = ""): Unit = {
    val contents = if (source.isEmpty) HelloWorldSource else source
    val srcDir = projectDir.toPath.resolve("src").resolve("main").resolve("scala")
    Files.createDirectories(srcDir)
    val srcFile = srcDir.resolve("Source1.scala")
    Files.write(srcFile, contents.getBytes(StandardCharsets.UTF_8))
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
