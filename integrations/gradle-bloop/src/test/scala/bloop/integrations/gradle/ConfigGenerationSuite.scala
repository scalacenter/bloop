package bloop.integrations.gradle

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import bloop.cli.Commands
import bloop.config.Config.Platform.Jvm
import bloop.config.{Config, Tag}
import bloop.config.Config.{CompileSetup, JavaThenScala, Mixed, Platform}
import bloop.data.WorkspaceSettings
import bloop.engine.{Build, Run, State}
import bloop.io.AbsolutePath
import bloop.logging.BloopLogger
import bloop.util.TestUtil
import org.gradle.testkit.runner.{BuildResult, GradleRunner}
import org.gradle.testkit.runner.TaskOutcome._
import org.junit._
import org.junit.Assert._
import org.junit.rules.TemporaryFolder
import bloop.engine.BuildLoader
import scala.collection.JavaConverters._
import io.github.classgraph.ClassGraph

/*
 * To remote debug the ConfigGenerationSuite...
 * - change "gradlePluginBuildSettings" "Keys.fork in Test" in BuildPlugin.scala to "false"
 * - scala-library-2-18.1.jar disappears from classpath when fork=false.  Add manually for now to getClasspath output
 * - add ".withDebug(true)" to the GradleRunner in the particular test to debug
 * - run tests under gradleBloop212 project
 */

// minimum supported version
class ConfigGenerationSuite50 extends ConfigGenerationSuite {
  protected val gradleVersion: String = "5.0"
}

// maximum supported version
class ConfigGenerationSuite64 extends ConfigGenerationSuite {
  protected val gradleVersion: String = "6.4"
}

abstract class ConfigGenerationSuite {
  protected val gradleVersion: String

  // folder to put test build scripts and java/scala source files
  private val testProjectDir_ = new TemporaryFolder()
  @Rule def testProjectDir: TemporaryFolder = testProjectDir_

  @Test def pluginCanBeApplied(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
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
        .withPluginClasspath(getClasspath)
        .withArguments("tasks")
        .build()

    assertEquals(SUCCESS, result.task(":tasks").getOutcome)
  }

  @Test def bloopInstallTaskAdded(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
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
        .withPluginClasspath(getClasspath)
        .withArguments("tasks", "--all")
        .build()

    assert(result.getOutput.linesIterator.contains("bloopInstall"))
  }

  @Test def worksWithScala211Project(): Unit = {
    worksWithGivenScalaVersion("2.11.12")
  }

  @Test def worksWithScala212Project(): Unit = {
    worksWithGivenScalaVersion("2.12.8")
  }

  @Test def worksWithScala213Project(): Unit = {
    worksWithGivenScalaVersion("2.13.1")
  }

  @Test def worksWithDotty21(): Unit = {
    worksWithDotty("0.21.0-RC1")
  }

  @Test def worksWithDottyLatest(): Unit = {
    worksWithDotty("Latest")
  }

  def worksWithDotty(version: String): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
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
         |bloop {
         |  dottyVersion = "$version"
         |}
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile 'org.scala-lang:scala-library:2.13.1'
         |}
      """.stripMargin
    )

    createHelloWorldScalaSource(testProjectDir.getRoot)

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val projectFile = new File(bloopDir, s"${projectName}.json")
    val configFile = readValidBloopConfig(projectFile)

    assert(configFile.project.`scala`.isDefined)

    if (version.toUpperCase == "LATEST")
      assertNotEquals(version, configFile.project.`scala`.get.version)
    else
      assertEquals(version, configFile.project.`scala`.get.version)
    assertEquals("ch.epfl.lamp", configFile.project.`scala`.get.organization)
    assert(configFile.project.`scala`.get.jars.exists(_.toString.contains("dotty-compiler")))
    assert(hasBothClasspathsEntryName(configFile, "dotty-library"))
    assert(hasBothClasspathsEntryName(configFile, "scala-library"))

    val idxDottyLib = idxOfClasspathEntryName(configFile, "dotty-library")
    val idxScalaLib = idxOfClasspathEntryName(configFile, "scala-library")

    assert(idxDottyLib < idxScalaLib)

    assert(hasTag(configFile, Tag.Library))
  }

  @Test def worksWithInputVariables(): Unit = {

    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
    writeBuildScript(
      buildFile,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'scala'
        |apply plugin: 'bloop'
        |
        |bloop {
        |  targetDir      = file("testDir")
        |  stdLibName     = "testLibName"
        |  includeSources = false
        |  includeJavadoc = false
        |}
        |
        |repositories {
        |  mavenCentral()
        |}
        |
        |dependencies {
        |  compile 'org.scala-lang:scala-library:2.12.8'
        |}
      """.stripMargin
    )

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    // non-".bloop" dir
    val bloopDir = new File(testProjectDir.getRoot, "testDir")
    val bloop = new File(bloopDir, s"${projectName}.json")

    val config = readValidBloopConfig(bloop)
    // no Scala library should be found because it's checking against "testLibName"
    assert(config.project.`scala`.isEmpty)
    // no source or JavaDoc artifacts should exist
    assert(config.project.resolution.nonEmpty)
    assert(
      !config.project.resolution.exists(
        res =>
          res.modules
            .exists(conf => conf.artifacts.exists(artifact => artifact.classifier.nonEmpty))
      )
    )
  }

  @Test def worksWithTransientProjectDependencies(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    testProjectDir.newFolder("a", "src", "main", "scala")
    testProjectDir.newFolder("a", "src", "test", "scala")
    val buildDirB = testProjectDir.newFolder("b")
    testProjectDir.newFolder("b", "src", "main", "scala")
    testProjectDir.newFolder("b", "src", "test", "scala")
    val buildDirC = testProjectDir.newFolder("c")
    testProjectDir.newFolder("c", "src", "main", "scala")
    testProjectDir.newFolder("c", "src", "test", "scala")
    val buildDirD = testProjectDir.newFolder("d")
    testProjectDir.newFolder("d", "src", "main", "scala")
    testProjectDir.newFolder("d", "src", "test", "scala")
    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")
    val buildFileC = new File(buildDirC, "build.gradle")
    val buildFileD = new File(buildDirD, "build.gradle")

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
         |  compile 'org.scala-lang:scala-library:2.12.8'
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
         |  implementation 'org.typelevel:cats-core_2.12:1.2.0'
         |  compile project(':a')
         |  compile project(':c')
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileC,
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
         |  compile 'org.scala-lang:scala-library:2.12.8'
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileD,
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
         |  compile project(':b')
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'scala-multi-projects'
        |include 'a'
        |include 'b'
        |include 'c'
        |include 'd'
      """.stripMargin
    )

    createHelloWorldScalaSource(buildDirA, "package x { trait A }")
    createHelloWorldScalaSource(buildDirB, "package y { trait B extends x.A { println(new z.C) } }")
    createHelloWorldScalaSource(buildDirC, "package z { class C }")
    createHelloWorldScalaSource(
      buildDirD,
      "package zz { class D extends x.A { println(new z.C) } }"
    )

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopNone = new File(bloopDir, s"${projectName}.json")
    val bloopA = new File(bloopDir, "a.json")
    val bloopB = new File(bloopDir, "b.json")
    val bloopC = new File(bloopDir, "c.json")
    val bloopD = new File(bloopDir, "d.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopBTest = new File(bloopDir, "b-test.json")
    val bloopCTest = new File(bloopDir, "c-test.json")
    val bloopDTest = new File(bloopDir, "d-test.json")

    assert(!bloopNone.exists())
    val configA = readValidBloopConfig(bloopA)
    val configB = readValidBloopConfig(bloopB)
    val configC = readValidBloopConfig(bloopC)
    val configD = readValidBloopConfig(bloopD)
    val configATest = readValidBloopConfig(bloopATest)
    val configBTest = readValidBloopConfig(bloopBTest)
    val configCTest = readValidBloopConfig(bloopCTest)
    val configDTest = readValidBloopConfig(bloopDTest)

    assert(configA.project.`scala`.exists(_.version == "2.12.8"))
    assert(configB.project.`scala`.exists(_.version == "2.12.8"))
    assert(configC.project.`scala`.exists(_.version == "2.12.8"))
    assert(configD.project.`scala`.exists(_.version == "2.12.8"))
    assert(configA.project.dependencies.isEmpty)
    assertEquals(List("a", "c"), configB.project.dependencies.sorted)
    assert(configC.project.dependencies.isEmpty)
    assertEquals(List("b"), configD.project.dependencies.sorted)
    assertEquals(List("a"), configATest.project.dependencies)
    assertEquals(List("a", "b", "c"), configBTest.project.dependencies.sorted)
    assertEquals(List("c"), configCTest.project.dependencies)
    assertEquals(List("b", "d"), configDTest.project.dependencies.sorted)

    assert(hasTag(configA, Tag.Library))
    assert(hasTag(configATest, Tag.Test))
    assert(hasTag(configB, Tag.Library))
    assert(hasTag(configBTest, Tag.Test))
    assert(hasTag(configC, Tag.Library))
    assert(hasTag(configCTest, Tag.Test))
    assert(hasTag(configD, Tag.Library))
    assert(hasTag(configDTest, Tag.Test))

    def assertSources(config: Config.File, entryName: String): Unit = {
      assertTrue(
        s"Resolution field for ${config.project.name} does not exist",
        config.project.resolution.isDefined
      )
      config.project.resolution.foreach { resolution =>
        val sources = resolution.modules.find(
          module =>
            module.name.contains(entryName) && module.artifacts
              .exists(_.classifier.contains("sources"))
        )
        assertTrue(s"Sources for $entryName do not exist", sources.isDefined)
        assertTrue(
          s"There are more sources than one for $entryName:\n${sources.get.artifacts.mkString("\n")}",
          sources.exists(_.artifacts.size == 2)
        )
      }
    }

    assert(hasBothClasspathsEntryName(configA, "scala-library"))
    assertSources(configA, "scala-library")
    assert(hasBothClasspathsEntryName(configB, "scala-library"))
    assertSources(configB, "scala-library")
    assert(hasBothClasspathsEntryName(configC, "scala-library"))
    assertSources(configC, "scala-library")
    assert(hasBothClasspathsEntryName(configATest, "scala-library"))
    assertSources(configATest, "scala-library")
    assert(hasBothClasspathsEntryName(configBTest, "scala-library"))
    assertSources(configBTest, "scala-library")
    assert(hasBothClasspathsEntryName(configCTest, "scala-library"))
    assertSources(configCTest, "scala-library")
    assert(hasBothClasspathsEntryName(configATest, "/a/build/classes"))
    assert(hasBothClasspathsEntryName(configCTest, "/c/build/classes"))
    assert(hasBothClasspathsEntryName(configB, "cats-core"))
    assertSources(configB, "cats-core")
    assert(hasBothClasspathsEntryName(configB, "/a/build/classes"))
    assert(hasBothClasspathsEntryName(configB, "/c/build/classes"))
    assert(hasBothClasspathsEntryName(configBTest, "cats-core"))
    assertSources(configBTest, "cats-core")
    assert(hasBothClasspathsEntryName(configBTest, "/a/build/classes"))
    assert(hasBothClasspathsEntryName(configBTest, "/b/build/classes"))
    assert(hasBothClasspathsEntryName(configBTest, "/c/build/classes"))
    assert(hasBothClasspathsEntryName(configD, "/a/build/classes"))
    assert(hasBothClasspathsEntryName(configD, "/b/build/classes"))
    assert(hasBothClasspathsEntryName(configD, "/c/build/classes"))
    assert(hasBothClasspathsEntryName(configDTest, "/a/build/classes"))
    assert(hasBothClasspathsEntryName(configDTest, "/b/build/classes"))
    assert(hasBothClasspathsEntryName(configDTest, "/c/build/classes"))
    assert(hasBothClasspathsEntryName(configDTest, "/d/build/classes"))

    assert(compileBloopProject("b", bloopDir).status.isOk)
    assert(compileBloopProject("d", bloopDir).status.isOk)
  }

  // problem here is that to specify the test sourceset of project b depends on the test sourceset of project a using
  // testCompile project(':a').sourceSets.test.output
  // means that it points directly at the source set directory instead of the project + sourceset
  @Test def worksWithSourceSetDependencies(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    testProjectDir.newFolder("a", "src", "main", "scala")
    testProjectDir.newFolder("a", "src", "test", "scala")
    val buildDirB = testProjectDir.newFolder("b")
    testProjectDir.newFolder("b", "src", "main", "scala")
    testProjectDir.newFolder("b", "src", "test", "scala")
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
         |  compile 'org.scala-lang:scala-library:2.12.8'
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
         |  compile 'org.scala-lang:scala-library:2.12.8'
         |  testImplementation project(':a').sourceSets.test.output
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

    createHelloWorldScalaTestSource(buildDirA, "package x { trait A }")
    createHelloWorldScalaTestSource(buildDirB, "package y { trait B extends x.A { } }")

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopNone = new File(bloopDir, s"${projectName}.json")
    val bloopA = new File(bloopDir, "a.json")
    val bloopB = new File(bloopDir, "b.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopBTest = new File(bloopDir, "b-test.json")

    assert(!bloopNone.exists())
    val configA = readValidBloopConfig(bloopA)
    val configB = readValidBloopConfig(bloopB)
    val configATest = readValidBloopConfig(bloopATest)
    val configBTest = readValidBloopConfig(bloopBTest)

    assert(hasTag(configA, Tag.Library))
    assert(hasTag(configB, Tag.Library))
    assert(hasTag(configATest, Tag.Test))
    assert(hasTag(configBTest, Tag.Test))
    assert(configA.project.dependencies.isEmpty)
    assert(configB.project.dependencies.isEmpty)
    assertEquals(List("a"), configATest.project.dependencies.sorted)
    assertEquals(List("a-test", "b"), configBTest.project.dependencies.sorted)

    assert(!hasCompileClasspathEntryName(configB, "/a/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configB, "/a/build/classes"))
    assert(!hasCompileClasspathEntryName(configB, "/a-test/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configB, "/a-test/build/classes"))
    assert(!hasCompileClasspathEntryName(configBTest, "/a/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configBTest, "/a/build/classes"))
    assert(hasBothClasspathsEntryName(configBTest, "/b/build/classes"))
    assert(hasBothClasspathsEntryName(configBTest, "/a-test/build/classes"))

    assert(compileBloopProject("b", bloopDir).status.isOk)
  }

  // problem here is that to specify the test sourceset of project b depends on the test sourceset of project a using
  // additional configuration + artifacts
  @Test def worksWithConfigurationDependencies(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    testProjectDir.newFolder("a", "src", "main", "scala")
    testProjectDir.newFolder("a", "src", "test", "scala")
    val buildDirB = testProjectDir.newFolder("b")
    testProjectDir.newFolder("b", "src", "main", "scala")
    testProjectDir.newFolder("b", "src", "test", "scala")
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
         |configurations {
         |  testArtifacts.extendsFrom testRuntime
         |}
         |
         |task testJar(type: Jar) {
         |  classifier = 'tests'
         |  from sourceSets.test.output
         |}
         |
         |artifacts {
         |  testArtifacts testJar
         |}
         |
         |dependencies {
         |  compile 'org.scala-lang:scala-library:2.12.8'
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
         |  compile 'org.scala-lang:scala-library:2.12.8'
         |  testImplementation project( path: ':a', configuration: 'testArtifacts')
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

    createHelloWorldScalaTestSource(buildDirA, "package x { trait A }")
    createHelloWorldScalaTestSource(buildDirB, "package y { trait B extends x.A { } }")

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopNone = new File(bloopDir, s"${projectName}.json")
    val bloopA = new File(bloopDir, "a.json")
    val bloopB = new File(bloopDir, "b.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopBTest = new File(bloopDir, "b-test.json")

    assert(!bloopNone.exists())
    val configA = readValidBloopConfig(bloopA)
    val configB = readValidBloopConfig(bloopB)
    val configATest = readValidBloopConfig(bloopATest)
    val configBTest = readValidBloopConfig(bloopBTest)

    assert(hasTag(configA, Tag.Library))
    assert(hasTag(configB, Tag.Library))
    assert(hasTag(configATest, Tag.Test))
    assert(hasTag(configBTest, Tag.Test))
    assert(configA.project.dependencies.isEmpty)
    assert(configB.project.dependencies.isEmpty)
    assertEquals(List("a"), configATest.project.dependencies.sorted)
    assertEquals(List("a-test", "b"), configBTest.project.dependencies.sorted)

    assert(!hasCompileClasspathEntryName(configB, "/a/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configB, "/a/build/classes"))
    assert(!hasCompileClasspathEntryName(configB, "/a-test/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configB, "/a-test/build/classes"))
    assert(hasBothClasspathsEntryName(configBTest, "/a-test/build/classes"))
    assert(hasBothClasspathsEntryName(configBTest, "/b/build/classes"))

    assert(compileBloopProject("b-test", bloopDir).status.isOk)
  }

  @Test def encodingOptionGeneratedCorrectly(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
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
         |  compile group: 'org.scala-lang', name: 'scala-library', version: '2.12.8'
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
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopFile = new File(new File(testProjectDir.getRoot, ".bloop"), projectName + ".json")

    val resultConfig = readValidBloopConfig(bloopFile)

    assertEquals(
      List("-deprecation", "-encoding", "utf8", "-unchecked"),
      resultConfig.project.`scala`.get.options
    )
  }

  @Test def flagsWithArgsGeneratedCorrectly(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
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
         |  compile group: 'org.scala-lang', name: 'scala-library', version: '2.12.8'
         |}
         |
         |tasks.withType(JavaCompile) {
         |  sourceCompatibility = JavaVersion.VERSION_1_2
         |  targetCompatibility = JavaVersion.VERSION_1_3
         |}
         |
         |tasks.withType(ScalaCompile) {
         |  scalaCompileOptions.encoding = "utf8"
         |	scalaCompileOptions.additionalParameters = [
         |    "-deprecation",
         |    "-Yjar-compression-level", "0",
         |    "-Ybackend-parallelism", "8",
         |    "-unchecked"]
         |}
         |
      """.stripMargin
    )

    createHelloWorldScalaSource(testProjectDir.getRoot)

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopFile = new File(new File(testProjectDir.getRoot, ".bloop"), projectName + ".json")

    val resultConfig = readValidBloopConfig(bloopFile)

    assertEquals(
      List(
        "-Ybackend-parallelism",
        "8",
        "-Yjar-compression-level",
        "0",
        "-deprecation",
        "-encoding",
        "utf8",
        "-unchecked"
      ),
      resultConfig.project.`scala`.get.options
    )
    val expectedOptions =
      List("-source", "1.2", "-target", "1.3", "-g", "-sourcepath", "-XDuseUnsharedTable=true")
    val obtainedOptions = resultConfig.project.java.get.options
    assert(expectedOptions.forall(opt => obtainedOptions.contains(opt)))
  }

  @Test def doesNotCreateEmptyProjects(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    val buildDirB = testProjectDir.newFolder("b")
    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")

    writeBuildScript(
      buildFileA,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java'
        |apply plugin: 'bloop'
        |
        |sourceSets.main {
        |  resources.srcDirs = ["$projectDir/resources"]
        |}
        |sourceSets.test {
        |  resources.srcDirs = ["$projectDir/testresources"]
        |}
        |
      """.stripMargin
    )

    writeBuildScript(
      buildFileB,
      """
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
        |dependencies {
        |  compile 'org.scala-lang:scala-library:2.12.8'
        |  compile project(':a')
        |}
        |
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

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopNone = new File(bloopDir, s"${projectName}.json")
    val bloopA = new File(bloopDir, "a.json")
    val bloopB = new File(bloopDir, "b.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopBTest = new File(bloopDir, "b-test.json")

    // projects shouldn't be created because they have no dependencies and all source/resources directories are empty
    assert(!bloopNone.exists())
    assert(bloopA.exists())
    assert(!bloopATest.exists())
    assert(bloopB.exists())
    assert(!bloopBTest.exists())
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
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    assert(result.getOutput.contains("Ignoring 'bloopInstall' on non-Scala and non-Java project"))
    val projectName = testProjectDir.getRoot.getName
    val bloopFile = new File(new File(testProjectDir.getRoot, ".bloop"), projectName + ".json")
    assert(!bloopFile.exists())
  }

  @Test def generateConfigFileForNonJavaNonScalaProjectDependencies(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    val buildDirB = testProjectDir.newFolder("b")
    testProjectDir.newFolder("b", "src", "test", "scala")
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
      """
        |import org.gradle.internal.jvm.Jvm
        |
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
        |  testRuntime files("${System.properties['java.home']}/../lib/tools.jar")
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
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopNone = new File(bloopDir, s"$projectName.json")
    val bloopA = new File(bloopDir, "a.json")
    val bloopB = new File(bloopDir, "b.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopBTest = new File(bloopDir, "b-test.json")

    assert(!bloopNone.exists())
    assert(!bloopA.exists())
    assert(!bloopATest.exists())
    val configB = readValidBloopConfig(bloopB)
    val configBTest = readValidBloopConfig(bloopBTest)
    assert(configB.project.`scala`.exists(_.version == "2.12.6"))
    assert(hasTag(configB, Tag.Library))
    assert(hasTag(configBTest, Tag.Test))
    assertEquals(Nil, configB.project.dependencies)
    assertEquals(List("b"), configBTest.project.dependencies)

    assert(hasBothClasspathsEntryName(configB, "scala-library"))
    assert(hasBothClasspathsEntryName(configBTest, "scala-library"))
    assert(hasBothClasspathsEntryName(configB, "cats-core"))
    assert(hasBothClasspathsEntryName(configBTest, "cats-core"))
    assert(!hasCompileClasspathEntryName(configBTest, "tools.jar"))
    assert(hasRuntimeClasspathEntryName(configBTest, "tools.jar"))

    assert(compileBloopProject("b", bloopDir).status.isOk)
  }

  @Test def generateConfigFileForJavaOnlyProjects(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    writeBuildScript(
      buildFile,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |apply plugin: 'application'
         |apply plugin: 'java'
         |apply plugin: 'bloop'
         |
         |mainClassName = 'org.main.name'
         |
      """.stripMargin
    )

    createHelloWorldJavaSource()
    createHelloWorldJavaTestSource()

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val projectFile = new File(bloopDir, s"${projectName}.json")
    val projectTestFile = new File(bloopDir, s"${projectName}-test.json")
    val projectConfig = readValidBloopConfig(projectFile)
    assert(!projectConfig.project.`scala`.isDefined)
    assert(projectConfig.project.dependencies.isEmpty)
    assert(projectConfig.project.classpath.isEmpty)
    assert(hasTag(projectConfig, Tag.Library))

    val projectTestConfig = readValidBloopConfig(projectTestFile)
    assert(!projectConfig.project.`scala`.isDefined)
    assert(projectTestConfig.project.dependencies == List(projectName))
    assert(hasTag(projectTestConfig, Tag.Test))
    assert(compileBloopProject(s"${projectName}-test", bloopDir).status.isOk)
  }

  @Test def importsPlatformJavaHomeAndOpts(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    writeBuildScript(
      buildFile,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |apply plugin: 'java'
         |apply plugin: 'bloop'
         |
         |compileJava {
         |  options.forkOptions.javaHome = file("/opt/jdk11")
         |  options.forkOptions.jvmArgs += "-XX:MaxMetaSpaceSize=512m"
         |  options.forkOptions.memoryInitialSize = "1g"
         |  options.forkOptions.memoryMaximumSize = "2g"
         |}
         |
      """.stripMargin
    )

    createHelloWorldJavaSource()
    createHelloWorldJavaTestSource()

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val projectFile = new File(bloopDir, s"${projectName}.json")
    val projectConfig = readValidBloopConfig(projectFile)
    val platform = projectConfig.project.platform
    assert(platform.isDefined)
    assert(platform.get.isInstanceOf[Platform.Jvm])
    val config = platform.get.asInstanceOf[Platform.Jvm].config
    if (!scala.util.Properties.isWin) assert(config.home.contains(Paths.get("/opt/jdk11")))
    assert(config.options.toSet == Set("-XX:MaxMetaSpaceSize=512m", "-Xms1g", "-Xmx2g"))
  }

  @Test def importsTestSystemProperties(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    writeBuildScript(
      buildFile,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |apply plugin: 'java'
         |apply plugin: 'bloop'
         |
         |test.systemProperty("property", "value")
         |test.jvmArgs = ["-XX:+UseG1GC", "-verbose:gc"]
         |test.minHeapSize = "1g"
         |test.maxHeapSize = "2g"
         |
      """.stripMargin
    )

    createHelloWorldJavaSource()
    createHelloWorldJavaTestSource()

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val projectFile = new File(bloopDir, s"${projectName}-test.json")
    val projectConfig = readValidBloopConfig(projectFile)
    assert(projectConfig.project.test.isDefined)
    val platform = projectConfig.project.platform
    assert(platform.isDefined)
    assert(platform.get.isInstanceOf[Platform.Jvm])
    val config = platform.get.asInstanceOf[Platform.Jvm].config
    assert(
      config.options.toSet == Set(
        "-XX:+UseG1GC",
        "-verbose:gc",
        "-Xms1g",
        "-Xmx2g",
        "-Dproperty=value"
      )
    )
  }

  @Test def setsCorrectCompileOrder(): Unit = {
    def getSetup(buildDir: File): CompileSetup = {
      GradleRunner
        .create()
        .withGradleVersion(gradleVersion)
        .withProjectDir(buildDir)
        .withPluginClasspath(getClasspath)
        .withArguments("bloopInstall", "-Si")
        .build()

      val projectName = buildDir.getName
      val bloopDir = new File(buildDir, ".bloop")
      val projectFile = new File(bloopDir, s"${projectName}.json")
      val projectConfig = readValidBloopConfig(projectFile)
      val scalaConfig = projectConfig.project.`scala`.get
      scalaConfig.setup.getOrElse(CompileSetup.empty)
    }

    val buildDirA = testProjectDir.newFolder("a")
    val buildFileA = new File(buildDirA, "build.gradle")
    writeBuildScript(
      buildFileA,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |apply plugin: 'java'
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |sourceSets {
         |  main {
         |    java {  srcDirs = ['src/main/java'] }
         |    scala {  srcDirs = ['src/main/scala'] }
         |  }
         |}
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile 'org.scala-lang:scala-library:2.12.8'
         |}
      """.stripMargin
    )

    val setupA = getSetup(buildDirA)
    assert(setupA.order == JavaThenScala)

    val buildDirB = testProjectDir.newFolder("b")
    val buildFileB = new File(buildDirB, "build.gradle")
    writeBuildScript(
      buildFileB,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |apply plugin: 'java'
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |sourceSets {
         |  main {
         |    java { srcDirs = [] }
         |    scala {  srcDirs = ['src/main/scala', 'src/main/java'] }
         |  }
         |}
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile 'org.scala-lang:scala-library:2.12.8'
         |}
      """.stripMargin
    )

    val setupB = getSetup(buildDirB)
    assert(setupB.order == Mixed)
  }

  @Test def maintainsClassPathOrder(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    val buildDirB = testProjectDir.newFolder("b")
    val buildDirC = testProjectDir.newFolder("c")
    val buildDirD = testProjectDir.newFolder("d")
    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")
    val buildFileC = new File(buildDirC, "build.gradle")
    val buildFileD = new File(buildDirD, "build.gradle")

    writeBuildScript(
      buildFileA,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java'
        |apply plugin: 'bloop'
      """.stripMargin
    )

    writeBuildScript(
      buildFileB,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java'
        |apply plugin: 'bloop'
      """.stripMargin
    )

    writeBuildScript(
      buildFileC,
      """
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
        |dependencies {
        |  compile 'org.scala-lang:scala-library:2.12.8'
        |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileD,
      """
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
        |dependencies {
        |  compile project(':c')
        |  compile 'org.typelevel:cats-core_2.12:1.2.0'
        |  compile project(':a')
        |  compile project(':b')
        |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'scala-multi-projects'
        |include 'a'
        |include 'b'
        |include 'c'
        |include 'd'
      """.stripMargin
    )

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopD = new File(bloopDir, "d.json")

    val configD = readValidBloopConfig(bloopD)
    assertEquals(List("c", "a", "b"), configD.project.dependencies)

    val idxA = idxOfClasspathEntryName(configD, "/a/build/classes")
    val idxB = idxOfClasspathEntryName(configD, "/b/build/classes")
    val idxC = idxOfClasspathEntryName(configD, "/c/build/classes")

    assert(idxC < idxA)
    assert(idxA < idxB)
    assert(idxB < configD.project.classpath.size)
  }

  @Test def handlesCompileAndRuntimeClassPath(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    val buildDirB = testProjectDir.newFolder("b")
    val buildDirC = testProjectDir.newFolder("c")
    val buildDirD = testProjectDir.newFolder("d")
    val buildDirE = testProjectDir.newFolder("e")
    val buildDirF = testProjectDir.newFolder("f")
    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")
    val buildFileC = new File(buildDirC, "build.gradle")
    val buildFileD = new File(buildDirD, "build.gradle")
    val buildFileE = new File(buildDirE, "build.gradle")
    val buildFileF = new File(buildDirF, "build.gradle")

    writeBuildScript(
      buildFileA,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java'
        |apply plugin: 'bloop'
      """.stripMargin
    )

    writeBuildScript(
      buildFileB,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java'
        |apply plugin: 'bloop'
      """.stripMargin
    )

    writeBuildScript(
      buildFileC,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java'
        |apply plugin: 'bloop'
      """.stripMargin
    )

    writeBuildScript(
      buildFileD,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java'
        |apply plugin: 'bloop'
      """.stripMargin
    )
    writeBuildScript(
      buildFileE,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java-library'
        |apply plugin: 'bloop'
        |
        |dependencies {
        |  api project(':a')
        |  implementation project(':b')
        |  compileOnly project(':c')
        |  runtimeOnly project(':d')
        |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileF,
      """
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
        |dependencies {
        |  compile project(':e')
        |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'scala-multi-projects'
        |include 'a'
        |include 'b'
        |include 'c'
        |include 'd'
        |include 'e'
        |include 'f'
      """.stripMargin
    )

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopE = new File(bloopDir, "e.json")
    val bloopF = new File(bloopDir, "f.json")
    val configE = readValidBloopConfig(bloopE)
    val configF = readValidBloopConfig(bloopF)

    assert(hasBothClasspathsEntryName(configE, "/a/build/classes"))
    assert(hasBothClasspathsEntryName(configE, "/b/build/classes"))
    assert(hasCompileClasspathEntryName(configE, "/c/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configE, "/c/build/classes"))
    assert(hasRuntimeClasspathEntryName(configE, "/d/build/classes"))
    assert(!hasCompileClasspathEntryName(configE, "/d/build/classes"))

    assert(hasBothClasspathsEntryName(configF, "/a/build/classes"))
    assert(!hasCompileClasspathEntryName(configF, "/b/build/classes"))
    assert(hasRuntimeClasspathEntryName(configF, "/b/build/classes"))
    assert(!hasCompileClasspathEntryName(configF, "/c/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configF, "/c/build/classes"))
    assert(!hasCompileClasspathEntryName(configF, "/d/build/classes"))
    assert(hasRuntimeClasspathEntryName(configF, "/d/build/classes"))
  }

  @Test def compilerPluginsGeneratedCorrectly(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
    writeBuildScript(
      buildFile,
      """
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
        |configurations {
        |    scalaCompilerPlugin
        |}
        |
        |dependencies {
        |  compile group: 'org.scala-lang', name: 'scala-library', version: '2.12.8'
        |  scalaCompilerPlugin "org.scalameta:semanticdb-scalac_2.12.8:4.1.9"
        |}
        |
        |tasks.withType(ScalaCompile) {
        |	 scalaCompileOptions.additionalParameters = [
        |      "-Xplugin:" + configurations.scalaCompilerPlugin.asPath,
        |      "-Yrangepos",
        |      "-P:semanticdb:sourceroot:${rootProject.projectDir}"
        |    ]
        |}
        |
        """.stripMargin
    )

    createHelloWorldScalaSource(testProjectDir.getRoot)

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopFile = new File(new File(testProjectDir.getRoot, ".bloop"), projectName + ".json")

    val resultConfig = readValidBloopConfig(bloopFile)

    assert(resultConfig.project.resolution.nonEmpty)
    assert(
      resultConfig.project.resolution.get.modules.exists(p => p.name == "semanticdb-scalac_2.12.8")
    )

    assert(
      resultConfig.project.`scala`.get.options
        .contains(s"-P:semanticdb:sourceroot:${testProjectDir.getRoot.getCanonicalPath}")
    )
    assert(resultConfig.project.`scala`.get.options.exists(p => p.startsWith("-Xplugin:")))
  }

  private def loadBloopState(configDir: File): State = {
    val logger = BloopLogger.default(configDir.toString)
    assert(Files.exists(configDir.toPath), "Does not exist: " + configDir)
    val configDirectory = AbsolutePath(configDir)
    val loadedProjects = BuildLoader.loadSynchronously(configDirectory, logger)
    val workspaceSettings = WorkspaceSettings.readFromFile(configDirectory, logger)
    val build = Build(configDirectory, loadedProjects, workspaceSettings)
    State.forTests(build, TestUtil.getCompilerCache(logger), logger)
  }

  private def compileBloopProject(
      projectName: String,
      bloopDir: File,
      verbose: Boolean = false
  ): State = {
    val state0 = loadBloopState(bloopDir)
    val state = if (verbose) state0.copy(logger = state0.logger.asVerbose) else state0
    val action = Run(Commands.Compile(List(projectName)))
    TestUtil.blockingExecute(action, state)
  }

  private def worksWithGivenScalaVersion(version: String): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
    testProjectDir.newFolder("src", "test", "scala")

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
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val projectFile = new File(bloopDir, s"${projectName}.json")
    val projectTestFile = new File(bloopDir, s"${projectName}-test.json")
    val configFile = readValidBloopConfig(projectFile)
    val configTestFile = readValidBloopConfig(projectTestFile)

    assert(configFile.project.`scala`.isDefined)
    assertEquals(version, configFile.project.`scala`.get.version)
    assert(configFile.project.classpath.nonEmpty)
    assert(configFile.project.dependencies.isEmpty)
    assert(hasTag(configFile, Tag.Library))

    assert(configTestFile.project.dependencies == List(projectName))
    assert(hasTag(configTestFile, Tag.Test))
    assert(compileBloopProject(s"${projectName}-test", bloopDir).status.isOk)
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

  private def createHelloWorldJavaTestSource(): Unit = {
    val srcDir = testProjectDir.newFolder("src", "test", "java")
    val srcFile = new File(srcDir, "HelloTest.java")
    val src =
      """
        |public class HelloTest {
        |    public static void main(String[] args) {
        |        System.out.println("Hello World test");
        |    }
        |}
      """.stripMargin
    Files.write(srcFile.toPath, src.getBytes(StandardCharsets.UTF_8))
    ()
  }

  private def createHelloWorldScalaTestSource(projectDir: File, source: String = ""): Unit = {
    val contents = if (source.isEmpty) HelloWorldSource else source
    val srcDir = projectDir.toPath.resolve("src").resolve("test").resolve("scala")
    Files.createDirectories(srcDir)
    val srcFile = srcDir.resolve("Source1.scala")
    Files.write(srcFile, contents.getBytes(StandardCharsets.UTF_8))
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
    assertTrue(s"The bloop project file should exist: $file", file.exists())
    val bytes = Files.readAllBytes(file.toPath)
    bloop.config.read(bytes) match {
      case Right(file) => file
      case Left(failure) =>
        throw new AssertionError(s"Failed to parse ${file.getAbsolutePath}: $failure")
    }
  }

  private def getClasspath: java.lang.Iterable[File] = {
    new ClassGraph().getClasspathFiles()
  }

  private def hasClasspathEntryName(entryName: String, classpath: List[Path]): Boolean = {
    val pathValidEntryName = entryName.replace('/', File.separatorChar)
    classpath.exists(_.toString.contains(pathValidEntryName))
  }

  private def hasRuntimeClasspathEntryName(config: Config.File, entryName: String): Boolean = {
    config.project.platform.exists {
      case platform: Jvm => platform.classpath.exists(hasClasspathEntryName(entryName, _))
      case _ => false
    }
  }

  private def hasCompileClasspathEntryName(config: Config.File, entryName: String): Boolean = {
    hasClasspathEntryName(entryName, config.project.classpath)
  }

  private def hasBothClasspathsEntryName(config: Config.File, entryName: String): Boolean = {
    hasCompileClasspathEntryName(config, entryName) &&
    hasRuntimeClasspathEntryName(config, entryName)
  }

  private def idxOfClasspathEntryName(config: Config.File, entryName: String): Int = {
    val pathValidEntryName = entryName.replace('/', File.separatorChar)
    config.project.classpath.takeWhile(!_.toString.contains(pathValidEntryName)).size
  }

  private def hasTag(config: Config.File, tag: String): Boolean = {
    config.project.tags.getOrElse(Nil).contains(tag)
  }

  private def writeBuildScript(buildFile: File, contents: String): Unit = {
    Files.write(buildFile.toPath, contents.getBytes(StandardCharsets.UTF_8))
    ()
  }
}
