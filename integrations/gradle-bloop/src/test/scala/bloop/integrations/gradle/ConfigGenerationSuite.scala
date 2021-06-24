package bloop.integrations.gradle

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import bloop.cli.Commands
import bloop.config.Config.Platform.Jvm
import bloop.config.{Config, Tag}
import bloop.config.Config.{CompileSetup, JavaThenScala, Mixed, Platform, TestFramework}
import bloop.config.utils.BaseConfigSuite
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
import io.github.classgraph.ClassGraph
import scala.collection.JavaConverters._

/*
 * To remote debug the ConfigGenerationSuite...
 * - change "gradlePluginBuildSettings" "Keys.fork in Test" in BuildPlugin.scala to "false"
 * - scala-library-2.12.8.jar disappears from classpath when fork=false.  Add manually for now to getClasspath output
 * - add ".withDebug(true)" to the GradleRunner in the particular test to debug and ".forwardOutput()" to see println
 * - run tests under gradleBloop212 project
 */

// minimum supported version
class ConfigGenerationSuite50 extends ConfigGenerationSuite {
  protected val gradleVersion: String = "5.0"
}

// maximum supported version
class ConfigGenerationSuite70 extends ConfigGenerationSuite {
  protected val gradleVersion: String = "7.0"
}

abstract class ConfigGenerationSuite extends BaseConfigSuite {
  protected val gradleVersion: String

  // folder to put test build scripts and java/scala source files
  private val testProjectDir_ = new TemporaryFolder()
  @Rule def testProjectDir: TemporaryFolder = testProjectDir_

  @Test def worksWithSourcesSetSourceNotEqualToResources(): Unit = {
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
         |sourceSets.main {
         |  java {
         |    srcDirs = ['src/main/java']
         |  }
         |  scala {
         |    srcDirs = ['src/main/scala']
         |  }
         |  resources {
         |    srcDirs = ['src/main/resources']
         |  }
         |}
         |
         |dependencies {
         |  implementation 'org.scala-lang:scala-library:2.12.8'
         |}
         |
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

    assert(hasPathEntryName("src/main/java", resultConfig.project.sources))
    assert(hasPathEntryName("src/main/scala", resultConfig.project.sources))
    assert(!hasPathEntryName("src/main/resources", resultConfig.project.sources))
    assert(resultConfig.project.sources.size == 2)
    assert(resultConfig.project.resources.isDefined)
    assert(hasPathEntryName("src/main/resources", resultConfig.project.resources.get))
    assert(resultConfig.project.resources.get.size == 1)
  }

  @Test def worksWithSourcesSetSourceEqualToResources(): Unit = {
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
         |sourceSets.main {
         |  java {
         |    srcDirs = ['src/main/scala']
         |  }
         |  scala {
         |    srcDirs = ['src/main/scala']
         |  }
         |  resources {
         |    srcDirs = ['src/main/scala']
         |  }
         |}
         |
         |dependencies {
         |  implementation 'org.scala-lang:scala-library:2.12.8'
         |}
         |
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
    assert(hasPathEntryName("src/main/scala", resultConfig.project.sources))
    assert(!hasPathEntryName("src/main/resources", resultConfig.project.sources))
    assert(resultConfig.project.sources.size == 1)
    assert(resultConfig.project.resources.isDefined)
    assert(hasPathEntryName("src/main/scala", resultConfig.project.resources.get))
    assert(resultConfig.project.resources.get.size == 1)
  }

  @Test def worksWithJavaCompilerAnnotationProcessor(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    writeBuildScript(
      buildFile,
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
        |  annotationProcessor "org.immutables:value:2.8.2"
        |  compileOnly "org.immutables:value:2.8.2"
        |}
        |
        """.stripMargin
    )

    val annotatedSource =
      """
        |import java.util.List;
        |import java.util.Set;
        |import org.immutables.value.Value;

        |@Value.Immutable
        |public abstract class FoobarValue {
        |  public abstract int foo();
        |  public abstract String bar();
        |  public abstract List<Integer> buz();
        |  public abstract Set<Long> crux();
        |}
      """.stripMargin
    val annotatedSourceUsage =
      """
        |import java.util.List;

        |public class FoobarValueMain {
        |  public static void main(String... args) {
        |    FoobarValue value = ImmutableFoobarValue.builder()
        |        .foo(2)
        |        .bar("Bar")
        |        .addBuz(1, 3, 4)
        |        .build(); // FoobarValue{foo=2, bar=Bar, buz=[1, 3, 4], crux={}}

        |    int foo = value.foo(); // 2

        |    List<Integer> buz = value.buz(); // ImmutableList.of(1, 3, 4)
        |  }
        |}
      """.stripMargin

    createSource(testProjectDir.getRoot, annotatedSource, "main", "FoobarValue", "java")
    createSource(testProjectDir.getRoot, annotatedSourceUsage, "main", "FoobarValueMain", "java")

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
    assert(hasCompileClasspathEntryName(resultConfig, "org.immutables"))
    assert(!hasRuntimeClasspathEntryName(resultConfig, "org.immutables"))
    assert(resultConfig.project.java.isDefined)
    val processorPath =
      resultConfig.project.java.get.options.dropWhile(_ != "-processorpath").drop(1)
    assert(processorPath.nonEmpty)
    assert(processorPath.head.contains("value-2.8.2.jar"))
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    assert(compileBloopProject(projectName, bloopDir).status.isOk)
  }

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

  @Test def worksWithSpecificDottyA(): Unit = {
    worksWithDotty("0.21.0-bin-20191101-3939747-NIGHTLY", "0.21.0-bin-20191101-3939747-NIGHTLY")
  }
  @Test def worksWithSpecificDottyB(): Unit = {
    worksWithDotty("0.21.0-RC1", "0.21.0-RC1")
  }
  @Test def worksWithSpecificDottyC(): Unit = {
    worksWithDotty("0.21.0", "0.21.0")
  }
  @Test def worksWithSpecificDottyD(): Unit = {
    worksWithDotty("0.21", "0.21.0")
  }
  @Test def worksWithSpecificScala3A(): Unit = {
    worksWithScala3(
      "3.0.0-M1-bin-20201015-8c56525-NIGHTLY",
      "3.0.0-M1-bin-20201015-8c56525-NIGHTLY"
    )
  }
  @Test def worksWithSpecificScala3B(): Unit = {
    worksWithScala3("3.0.0-M1", "3.0.0-M1")
  }
  @Test def worksWithSpecificScala3C(): Unit = {
    worksWithScala3("3.0.0", "3.0.0")
  }
  @Test def worksWithScala3LatestNightly(): Unit = {
    // newer releases mean we can't test for version
    worksWithScala3("Latest", "")
  }

  private def worksWithScala3(suppliedVersion: String, expectedVersion: String): Unit = {
    worksWithDottyOrScala3(
      suppliedVersion,
      expectedVersion,
      "org.scala-lang",
      "scala3-compiler",
      "scala3-library"
    )
  }

  private def worksWithDotty(suppliedVersion: String, expectedVersion: String): Unit = {
    worksWithDottyOrScala3(
      suppliedVersion,
      expectedVersion,
      "ch.epfl.lamp",
      "dotty-compiler",
      "dotty-library"
    )
  }

  private def worksWithDottyOrScala3(
      suppliedVersion: String,
      expectedVersion: String,
      expectedOrg: String,
      compilerName: String,
      libraryName: String
  ): Unit = {
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
         |  dottyVersion = "$suppliedVersion"
         |}
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  implementation 'org.scala-lang:scala-library:2.13.1'
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

    if (expectedVersion.isEmpty)
      assertNotEquals(suppliedVersion, configFile.project.`scala`.get.version)
    else
      assertEquals(expectedVersion, configFile.project.`scala`.get.version)
    assertEquals(expectedOrg, configFile.project.`scala`.get.organization)
    assert(configFile.project.`scala`.get.jars.exists(_.toString.contains(compilerName)))
    assert(hasBothClasspathsEntryName(configFile, libraryName))
    assert(hasBothClasspathsEntryName(configFile, "scala-library"))

    val idxDottyLib = idxOfClasspathEntryName(configFile, libraryName)
    val idxScalaLib = idxOfClasspathEntryName(configFile, "scala-library")

    assert(idxDottyLib < idxScalaLib)

    assert(hasTag(configFile, Tag.Library))

    assertNoConfigsHaveAnyJars(List(configFile), List(s"$projectName", s"$projectName-test"))
    assertAllConfigsMatchJarNames(List(configFile), List(libraryName))
  }

  @Test def failsOnMissingScalaLibrary(): Unit = {

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
        |  stdLibName = "testLibName"
        |}
        |
        |repositories {
        |  mavenCentral()
        |}
        |
        |dependencies {
        |  implementation 'org.scala-lang:scala-library:2.12.8'
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

    // no Scala library should be found because it's checking against "testLibName" so don't create the project
    assertFalse(projectFile.exists)
  }

  @Test def worksWithNoSourcesJavaDocs(): Unit = {

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
        |  includeSources = false
        |  includeJavadoc = false
        |}
        |
        |repositories {
        |  mavenCentral()
        |}
        |
        |dependencies {
        |  implementation 'org.scala-lang:scala-library:2.12.8'
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
    // source and JavaDoc artifacts should not exist
    assert(config.project.resolution.nonEmpty)
    assertFalse(
      config.project.resolution.exists(res =>
        res.modules
          .exists(conf =>
            conf.artifacts.exists(artifact => artifact.classifier.contains("javadoc"))
          )
      )
    )
    assertFalse(
      config.project.resolution.exists(res =>
        res.modules
          .exists(conf =>
            conf.artifacts.exists(artifact => artifact.classifier.contains("sources"))
          )
      )
    )
  }

  @Test def worksWithSourcesJavaDocs(): Unit = {

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
        |  includeSources = true
        |  includeJavadoc = true
        |}
        |
        |repositories {
        |  mavenCentral()
        |}
        |
        |dependencies {
        |  implementation 'org.scala-lang:scala-library:2.12.8'
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
    // source and JavaDoc artifacts should exist
    assert(config.project.resolution.nonEmpty)
    assert(
      config.project.resolution.exists(res =>
        res.modules
          .exists(conf =>
            conf.artifacts.exists(artifact => artifact.classifier.contains("javadoc"))
          )
      )
    )
    assert(
      config.project.resolution.exists(res =>
        res.modules
          .exists(conf =>
            conf.artifacts.exists(artifact => artifact.classifier.contains("sources"))
          )
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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
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
         |apply plugin: 'java-library'
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  implementation 'org.scala-lang:scala-library:2.12.8'
         |  implementation 'org.typelevel:cats-core_2.12:1.2.0'
         |  api project(':a')
         |  api project(':c')
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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
         |  implementation project(':b')
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

    assert(configA.project.workspaceDir.nonEmpty)
    // canonicalFile needed to pass tests on mac due to "/private/var" "/var" symlink
    assertEquals(
      testProjectDir.getRoot.getCanonicalFile,
      configA.project.workspaceDir.get.toFile.getCanonicalFile
    )

    assert(configA.project.`scala`.exists(_.version == "2.12.8"))
    assert(configB.project.`scala`.exists(_.version == "2.12.8"))
    assert(configC.project.`scala`.exists(_.version == "2.12.8"))
    assert(configD.project.`scala`.exists(_.version == "2.12.8"))
    assert(configA.project.dependencies.isEmpty)
    assertEquals(List("a", "c"), configB.project.dependencies.sorted)
    assert(configC.project.dependencies.isEmpty)
    assertEquals(List("a", "b", "c"), configD.project.dependencies.sorted)
    assertEquals(List("a"), configATest.project.dependencies)
    assertEquals(List("a", "b", "c"), configBTest.project.dependencies.sorted)
    assertEquals(List("c"), configCTest.project.dependencies)
    assertEquals(List("a", "b", "c", "d"), configDTest.project.dependencies.sorted)

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
        val sources = resolution.modules.find(module =>
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
    assert(hasBothClasspathsEntryName(configB, "/a/src/main/resources"))
    assert(hasBothClasspathsEntryName(configB, "/c/src/main/resources"))
    assert(hasBothClasspathsEntryName(configBTest, "cats-core"))
    assertSources(configBTest, "cats-core")
    assert(hasBothClasspathsEntryName(configBTest, "/a/build/classes"))
    assert(hasBothClasspathsEntryName(configBTest, "/b/build/classes"))
    assert(hasBothClasspathsEntryName(configBTest, "/c/build/classes"))
    assert(hasBothClasspathsEntryName(configBTest, "/a/src/main/resources"))
    assert(hasBothClasspathsEntryName(configBTest, "/b/src/main/resources"))
    assert(hasBothClasspathsEntryName(configBTest, "/c/src/main/resources"))
    assert(hasBothClasspathsEntryName(configD, "/a/build/classes"))
    assert(hasBothClasspathsEntryName(configD, "/b/build/classes"))
    assert(hasBothClasspathsEntryName(configD, "/c/build/classes"))
    assert(hasBothClasspathsEntryName(configD, "/a/src/main/resources"))
    assert(hasBothClasspathsEntryName(configD, "/b/src/main/resources"))
    assert(hasBothClasspathsEntryName(configD, "/c/src/main/resources"))
    assert(hasBothClasspathsEntryName(configDTest, "/a/build/classes"))
    assert(hasBothClasspathsEntryName(configDTest, "/b/build/classes"))
    assert(hasBothClasspathsEntryName(configDTest, "/c/build/classes"))
    assert(hasBothClasspathsEntryName(configDTest, "/d/build/classes"))
    assert(hasBothClasspathsEntryName(configDTest, "/a/src/main/resources"))
    assert(hasBothClasspathsEntryName(configDTest, "/b/src/main/resources"))
    assert(hasBothClasspathsEntryName(configDTest, "/c/src/main/resources"))
    assert(hasBothClasspathsEntryName(configDTest, "/d/src/main/resources"))

    assert(compileBloopProject("b", bloopDir).status.isOk)
    assert(compileBloopProject("d", bloopDir).status.isOk)

    assertNoConfigsHaveAnyJars(
      List(configA, configATest, configB, configBTest, configC, configCTest, configD, configDTest),
      List("a", "a-test", "b", "b-test", "c", "c-test", "d", "d-test")
    )

    assertAllConfigsHaveAllJars(
      List(configB, configBTest, configD, configDTest),
      List("cats-core_2.12-1.2.0")
    )
  }

  @Test def doesntOverIncludeOnClasspath(): Unit = {
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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
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
         |  implementation project(':a')
         |  implementation 'org.scala-lang:scala-library:2.12.8'
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
    createHelloWorldScalaTestSource(buildDirA, "package y { trait B }")
    createHelloWorldScalaTestSource(buildDirB, "package z { trait C extends x.A { } }")

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

    assert(configA.project.dependencies.isEmpty)
    assertEquals(List("a"), configATest.project.dependencies.sorted)
    assertEquals(List("a"), configB.project.dependencies.sorted)
    assertEquals(List("a", "b"), configBTest.project.dependencies.sorted)

    assert(!hasRuntimeClasspathEntryName(configA, "/build/resources/main"))
    assert(!hasRuntimeClasspathEntryName(configB, "/build/resources/main"))
    assert(!hasRuntimeClasspathEntryName(configATest, "/build/resources/main"))
    assert(!hasRuntimeClasspathEntryName(configBTest, "/build/resources/main"))

    assert(!hasCompileClasspathEntryName(configA, "/build/resources/main"))
    assert(!hasCompileClasspathEntryName(configB, "/build/resources/main"))
    assert(!hasCompileClasspathEntryName(configATest, "/build/resources/main"))
    assert(!hasCompileClasspathEntryName(configBTest, "/build/resources/main"))

    assert(!hasRuntimeClasspathEntryName(configA, "/a/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configB, "/b/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configATest, "/a-test/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configBTest, "/b-test/build/classes"))
  }

  @Test def worksWithIncludeFlat(): Unit = {
    val buildDirA = testProjectDir.newFolder("a")
    testProjectDir.newFolder("a", "src", "main", "scala")
    testProjectDir.newFolder("a", "src", "test", "scala")
    val buildDirB = testProjectDir.newFolder("b")
    testProjectDir.newFolder("b", "src", "main", "scala")
    testProjectDir.newFolder("b", "src", "test", "scala")
    val buildDirMaster = testProjectDir.newFolder("master")
    val buildSettings = new File(buildDirMaster, "settings.gradle")
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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
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
         |  implementation project(':a')
         |  implementation 'org.scala-lang:scala-library:2.12.8'
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'master'
        |includeFlat 'a'
        |includeFlat 'b'
      """.stripMargin
    )

    createHelloWorldScalaSource(buildDirA, "package x { trait A }")
    createHelloWorldScalaTestSource(buildDirA, "package y { trait B }")
    createHelloWorldScalaTestSource(buildDirB, "package z { trait C extends x.A { } }")

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(buildDirMaster)
      .withPluginClasspath(getClasspath)
      .withArguments("bloopInstall", "-Si")
      .forwardOutput()
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopNone = new File(bloopDir, s"${projectName}.json")
    val bloopA = new File(bloopDir, "a.json")
    val bloopB = new File(bloopDir, "b.json")
    val bloopMaster = new File(bloopDir, "master.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopBTest = new File(bloopDir, "b-test.json")

    assert(!bloopNone.exists())
    assert(!bloopMaster.exists())
    val configA = readValidBloopConfig(bloopA)
    val configB = readValidBloopConfig(bloopB)
    val configATest = readValidBloopConfig(bloopATest)
    val configBTest = readValidBloopConfig(bloopBTest)

    assert(configA.project.workspaceDir.nonEmpty)
    // canonicalFile needed to pass tests on mac due to "/private/var" "/var" symlink
    assertEquals(
      testProjectDir.getRoot.getCanonicalFile,
      configA.project.workspaceDir.get.toFile.getCanonicalFile
    )
    assert(configA.project.dependencies.isEmpty)
    assertEquals(List("a"), configATest.project.dependencies.sorted)
    assertEquals(List("a"), configB.project.dependencies.sorted)
    assertEquals(List("a", "b"), configBTest.project.dependencies.sorted)

    assert(!hasRuntimeClasspathEntryName(configA, "/build/resources/main"))
    assert(!hasRuntimeClasspathEntryName(configB, "/build/resources/main"))
    assert(!hasRuntimeClasspathEntryName(configATest, "/build/resources/main"))
    assert(!hasRuntimeClasspathEntryName(configBTest, "/build/resources/main"))

    assert(!hasCompileClasspathEntryName(configA, "/build/resources/main"))
    assert(!hasCompileClasspathEntryName(configB, "/build/resources/main"))
    assert(!hasCompileClasspathEntryName(configATest, "/build/resources/main"))
    assert(!hasCompileClasspathEntryName(configBTest, "/build/resources/main"))

    assert(!hasRuntimeClasspathEntryName(configA, "/a/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configB, "/b/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configATest, "/a-test/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configBTest, "/b-test/build/classes"))

    assert(hasBothClasspathsEntryName(configB, "/a/src/main/resources"))
    assert(hasBothClasspathsEntryName(configB, "/a/build/classes"))
  }

  @Test def worksWithDuplicateNestedProjectNames(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a", "b", "c", "foo")
    testProjectDir.newFolder("a", "b", "c", "src", "main", "scala")
    testProjectDir.newFolder("a", "b", "c", "src", "test", "scala")
    val buildDirB = testProjectDir.newFolder("b", "foo")
    testProjectDir.newFolder("b", "src", "main", "scala")
    testProjectDir.newFolder("b", "src", "test", "scala")
    val buildDirC = testProjectDir.newFolder("c", "foo")
    testProjectDir.newFolder("c", "src", "main", "scala")
    testProjectDir.newFolder("c", "src", "test", "scala")
    val buildDirD = testProjectDir.newFolder("d", "foo")
    testProjectDir.newFolder("d", "src", "main", "scala")
    testProjectDir.newFolder("d", "src", "test", "scala")

    val buildRoot = testProjectDir.newFile("build.gradle")
    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")
    val buildFileC = new File(buildDirC, "build.gradle")
    val buildFileD = new File(buildDirD, "build.gradle")

    writeBuildScript(
      buildRoot,
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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
         |}
      """.stripMargin
    )

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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
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
         |  implementation project(':a:b:c:foo')
         |  implementation project(':c:foo')
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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
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
         |  implementation project(':b:foo')
         |  implementation 'org.scala-lang:scala-library:2.12.8'
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'scala-multi-projects'
        |include 'a:b:c:foo'
        |include 'b:foo'
        |include 'c:foo'
        |include 'd:foo'
      """.stripMargin
    )

    createHelloWorldScalaSource(buildDirA, "package x { trait A }")
    createHelloWorldScalaSource(buildDirB, "package y { trait B }")
    createHelloWorldScalaSource(buildDirC, "package z { trait C }")
    createHelloWorldScalaSource(buildDirD, "package zz { trait D }")

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

    val bloopRoot = new File(bloopDir, "scala-multi-projects.json")
    val bloopA = new File(bloopDir, "b-c-foo.json")
    val bloopB = new File(bloopDir, "b-foo.json")
    val bloopC = new File(bloopDir, "scala-multi-projects-c-foo.json")
    val bloopD = new File(bloopDir, "d-foo.json")

    assert(!bloopNone.exists())
    val configRoot = readValidBloopConfig(bloopRoot)
    val configA = readValidBloopConfig(bloopA)
    val configB = readValidBloopConfig(bloopB)
    val configC = readValidBloopConfig(bloopC)
    val configD = readValidBloopConfig(bloopD)

    assert(compileBloopProject("b-foo", bloopDir).status.isOk)
    assert(compileBloopProject("d-foo", bloopDir).status.isOk)
  }

  @Test def worksWithMonoReposWithDuplicateProjectNames(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("code", "foo")
    val buildDirB = testProjectDir.newFolder("code", "bar")

    testProjectDir.newFolder("code", "foo", "src", "main", "scala")
    testProjectDir.newFolder("code", "foo", "src", "test", "scala")

    testProjectDir.newFolder("code", "bar", "src", "main", "scala")
    testProjectDir.newFolder("code", "bar", "src", "test", "scala")

    val buildDirC = testProjectDir.newFolder("infra", "foo")

    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")
    val buildFileC = new File(buildDirC, "build.gradle")

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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
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
         |  implementation project(':code:foo')
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileC,
      s"""
         |repositories {
         |  mavenCentral()
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'code-infra-mono-project'
        |include 'code:foo'
        |include 'code:bar'
        |include 'infra:foo'
      """.stripMargin
    )

    createHelloWorldScalaSource(buildDirA, "package x { trait A }")
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

    val bloopA = new File(bloopDir, "foo.json")
    val bloopB = new File(bloopDir, "bar.json")

    val shouldNotExistFiles = List("code-foo.json", "code-bar.json", "infra.json", "infra-foo.json")

    shouldNotExistFiles.foreach { file =>
      assert(!new File(bloopDir, file).exists(), s"$file should not have been created!")
    }

    val configA = readValidBloopConfig(bloopA)
    val configB = readValidBloopConfig(bloopB)

    assert(compileBloopProject("foo", bloopDir).status.isOk)
    assert(compileBloopProject("bar", bloopDir).status.isOk)
  }

  // problem here is that to specify the test sourceset of project b depends on the test sourceset of project a using
  // testImplementation project(':a').sourceSets.test.output
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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
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
    // "a" does not get included by Gradle using this dependency method
    assertEquals(List("a-test", "b"), configBTest.project.dependencies.sorted)

    assert(!hasCompileClasspathEntryName(configB, "/a/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configB, "/a/build/classes"))
    assert(!hasCompileClasspathEntryName(configB, "/a-test/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configB, "/a-test/build/classes"))
    assert(!hasCompileClasspathEntryName(configBTest, "/a/build/classes"))
    assert(!hasRuntimeClasspathEntryName(configBTest, "/a/build/classes"))
    assert(hasBothClasspathsEntryName(configBTest, "/b/build/classes"))
    assert(hasBothClasspathsEntryName(configBTest, "/a-test/build/classes"))

    assertNoConfigsHaveAnyJars(
      List(configA, configATest, configB, configBTest),
      List("a", "a-test", "b", "b-test")
    )

    assert(compileBloopProject("b", bloopDir).status.isOk)
  }

  @Test def worksWithConfigurationDependencies(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    testProjectDir.newFolder("a", "src", "main", "scala")
    val buildDirB = testProjectDir.newFolder("b")
    testProjectDir.newFolder("b", "src", "main", "scala")
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
         |  myConfiguration.extendsFrom runtimeElements
         |}
         |
         |dependencies {
         |  implementation 'org.scala-lang:scala-library:2.12.8'
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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
         |  implementation project( path: ':a', configuration: 'myConfiguration')
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
    createHelloWorldScalaSource(buildDirB, "package z { trait C extends x.A { } }")

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

    assert(!bloopNone.exists())
    val configA = readValidBloopConfig(bloopA)
    val configB = readValidBloopConfig(bloopB)

    assert(hasTag(configA, Tag.Library))
    assert(hasTag(configB, Tag.Library))
    assert(configA.project.dependencies.isEmpty)
    assertEquals(List("a"), configB.project.dependencies.sorted)

    assert(hasCompileClasspathEntryName(configB, "/a/build/classes"))
    assert(hasRuntimeClasspathEntryName(configB, "/a/build/classes"))

    assertNoConfigsHaveAnyJars(
      List(configA, configB),
      List("a", "b")
    )

    assert(compileBloopProject("b", bloopDir).status.isOk)
  }

  // problem here is that to specify the test sourceset of project b depends on the test sourceset of project a using
  // additional configuration + artifacts
  @Test def worksWithTestConfigurationDependencies(): Unit = {
    // testRuntime no longer consumable in 7.0
    if (gradleVersion < "7.0") {
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
           |  implementation 'org.scala-lang:scala-library:2.12.8'
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
           |  implementation 'org.scala-lang:scala-library:2.12.8'
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

      createHelloWorldScalaSource(buildDirA, "package x { trait A }")
      createHelloWorldScalaTestSource(buildDirA, "package y { trait B }")
      createHelloWorldScalaTestSource(buildDirB, "package z { trait C extends x.A with y.B { } }")

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
      assertEquals(List("a", "a-test", "b"), configBTest.project.dependencies.sorted)

      assert(!hasCompileClasspathEntryName(configB, "/a/build/classes"))
      assert(!hasRuntimeClasspathEntryName(configB, "/a/build/classes"))
      assert(!hasCompileClasspathEntryName(configB, "/a-test/build/classes"))
      assert(!hasRuntimeClasspathEntryName(configB, "/a-test/build/classes"))
      assert(hasBothClasspathsEntryName(configBTest, "/a-test/build/classes"))
      assert(hasBothClasspathsEntryName(configBTest, "/a/build/classes"))

      assertNoConfigsHaveAnyJars(
        List(configA, configATest, configB, configBTest),
        List("a", "a-test", "b", "b-test")
      )

      assert(compileBloopProject("b-test", bloopDir).status.isOk)
    }
  }

  @Test def worksWithTestFixtureDependencies(): Unit = {
    if (gradleVersion >= "5.6") {
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
           |apply plugin: 'java-test-fixtures'
           |
           |repositories {
           |  mavenCentral()
           |}
           |
           |dependencies {
           |  implementation 'org.scala-lang:scala-library:2.12.8'
           |  testFixturesImplementation 'org.scala-lang:scala-library:2.12.8'
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
           |  implementation 'org.scala-lang:scala-library:2.12.8'
           |  testImplementation(testFixtures(project(":a")))
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
      createHelloWorldScalaTestFixtureSource(buildDirA, "package y { trait B }")
      createHelloWorldScalaTestSource(buildDirB, "package z { trait C extends x.A with y.B { } }")

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
      val bloopATestFixtures = new File(bloopDir, "a-testFixtures.json")
      val bloopBTest = new File(bloopDir, "b-test.json")

      assert(!bloopNone.exists())
      val configA = readValidBloopConfig(bloopA)
      val configB = readValidBloopConfig(bloopB)
      val configATestFixtures = readValidBloopConfig(bloopATestFixtures)
      val configBTest = readValidBloopConfig(bloopBTest)

      assert(hasTag(configA, Tag.Library))
      assert(hasTag(configB, Tag.Library))
      assert(hasTag(configATestFixtures, Tag.Library))
      assert(hasTag(configBTest, Tag.Test))
      assert(configA.project.dependencies.isEmpty)
      assert(configB.project.dependencies.isEmpty)
      assertEquals(List("a"), configATestFixtures.project.dependencies.sorted)
      assertEquals(List("a", "a-testFixtures", "b"), configBTest.project.dependencies.sorted)

      assert(!hasCompileClasspathEntryName(configA, "/a-testFixtures/build/classes"))
      assert(!hasRuntimeClasspathEntryName(configA, "/a-testFixtures/build/classes"))
      assert(hasBothClasspathsEntryName(configATestFixtures, "/a/build/classes"))
      assert(!hasCompileClasspathEntryName(configB, "/a/build/classes"))
      assert(!hasRuntimeClasspathEntryName(configB, "/a/build/classes"))
      assert(!hasCompileClasspathEntryName(configB, "/a-test/build/classes"))
      assert(!hasRuntimeClasspathEntryName(configB, "/a-test/build/classes"))
      assert(!hasCompileClasspathEntryName(configB, "/a-testFixtures/build/classes"))
      assert(!hasRuntimeClasspathEntryName(configB, "/a-testFixtures/build/classes"))
      assert(hasBothClasspathsEntryName(configBTest, "/b/build/classes"))
      assert(hasBothClasspathsEntryName(configBTest, "/a/build/classes"))
      assert(hasBothClasspathsEntryName(configBTest, "/a-testFixtures/build/classes"))
      assert(!hasCompileClasspathEntryName(configBTest, "/a-test/build/classes"))
      assert(!hasRuntimeClasspathEntryName(configBTest, "/a-test/build/classes"))

      assertNoConfigsHaveAnyJars(
        List(configA, configATestFixtures, configB, configBTest),
        List("a", "a-test", "a-test-fixtures", "b", "b-test")
      )

      assert(compileBloopProject("b-test", bloopDir).status.isOk)
    }
  }

  @Test def worksWithLazyArchiveDependencies(): Unit = {
    // testRuntime no longer consumable in 7.0
    if (gradleVersion >= "4.9" && gradleVersion < "7.0") {
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
           |  add("testArtifacts", tasks.named("testJar"))
           |}
           |
           |dependencies {
           |  implementation 'org.scala-lang:scala-library:2.12.8'
           |  testImplementation 'org.scala-lang:scala-library:2.12.8'
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
           |  implementation 'org.scala-lang:scala-library:2.12.8'
           |  testImplementation 'org.scala-lang:scala-library:2.12.8'
           |  testImplementation project( path: ':a', configuration: "testArtifacts" )
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
      createHelloWorldScalaTestSource(buildDirA, "package y { trait B }")
      createHelloWorldScalaTestSource(buildDirB, "package z { trait C extends x.A with y.B { } }")

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
      assertEquals(List("a", "a-test", "b"), configBTest.project.dependencies.sorted)

      assert(!hasCompileClasspathEntryName(configB, "/a/build/classes"))
      assert(!hasRuntimeClasspathEntryName(configB, "/a/build/classes"))
      assert(!hasCompileClasspathEntryName(configB, "/a-test/build/classes"))
      assert(!hasRuntimeClasspathEntryName(configB, "/a-test/build/classes"))
      assert(hasBothClasspathsEntryName(configBTest, "/a-test/build/classes"))
      assert(hasBothClasspathsEntryName(configBTest, "/a/build/classes"))

      assertNoConfigsHaveAnyJars(
        List(configA, configATest, configB, configBTest),
        List("a", "a-test", "b", "b-test")
      )

      assert(compileBloopProject("b-test", bloopDir).status.isOk)
    }
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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
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
        |  implementation 'org.scala-lang:scala-library:2.12.8'
        |  implementation project(':a')
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

    createHelloWorldJavaSource(testProjectDir.getRoot)

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
        |  implementation 'org.typelevel:cats-core_2.12:1.2.0'
        |  implementation(project(path: ':a',  configuration: 'foo'))
        |  testRuntimeOnly files("${System.properties['java.home']}/../lib/tools.jar")
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

    assertNoConfigsHaveAnyJars(List(configB, configBTest), List("a", "a-test", "b", "b-test"))

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
         |application {
         |  applicationDefaultJvmArgs = ["-Dgreeting.language=en", "-Xmx16g"]
         |}
      """.stripMargin
    )

    createHelloWorldJavaSource(testProjectDir.getRoot)
    createHelloWorldJavaTestSource(testProjectDir.getRoot)

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
    assert(projectConfig.project.`scala`.isEmpty)
    assert(projectConfig.project.platform.nonEmpty)
    assert(projectConfig.project.platform.get.mainClass.nonEmpty)
    assertEquals("org.main.name", projectConfig.project.platform.get.mainClass.get)
    val platform = projectConfig.project.platform
    val config = platform.get.asInstanceOf[Platform.Jvm].config
    assertEquals(Set("-Dgreeting.language=en", "-Xmx16g"), config.options.toSet)
    assert(projectConfig.project.dependencies.isEmpty)
    assert(projectConfig.project.classpath.isEmpty)
    assert(hasTag(projectConfig, Tag.Library))

    val projectTestConfig = readValidBloopConfig(projectTestFile)
    assert(projectConfig.project.`scala`.isEmpty)
    assertEquals(projectTestConfig.project.dependencies, List(projectName))
    assert(hasTag(projectTestConfig, Tag.Test))
    assert(compileBloopProject(s"${projectName}-test", bloopDir).status.isOk)
  }

  @Test def generateConfigFileForOtherMainClass(): Unit = {
    if (gradleVersion >= "6.4") {
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
           |application {
           |  mainClass.set("org.main.name")
           |  applicationDefaultJvmArgs = ["-Dgreeting.language=en", "-Xmx16g"]
           |}
           |
      """.stripMargin
      )

      createHelloWorldJavaSource(testProjectDir.getRoot)

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
      assert(projectConfig.project.`scala`.isEmpty)
      val platform = projectConfig.project.platform
      assert(platform.isDefined)
      assert(platform.get.isInstanceOf[Platform.Jvm])
      assert(platform.get.mainClass.isDefined)
      assertEquals("org.main.name", platform.get.mainClass.get)
    }
  }

  @Test def generateConfigFileForNullMainClass(): Unit = {
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
      """.stripMargin
    )

    createHelloWorldJavaSource(testProjectDir.getRoot)
    createHelloWorldJavaTestSource(testProjectDir.getRoot)

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
    assert(projectConfig.project.`scala`.isEmpty)
    assert(projectConfig.project.dependencies.isEmpty)
    assert(projectConfig.project.classpath.isEmpty)
    assert(hasTag(projectConfig, Tag.Library))

    val projectTestConfig = readValidBloopConfig(projectTestFile)
    assert(projectConfig.project.`scala`.isEmpty)
    assert(projectConfig.project.platform.nonEmpty)
    assert(projectConfig.project.platform.get.mainClass.isEmpty)
    assertEquals(projectTestConfig.project.dependencies, List(projectName))
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

    createHelloWorldJavaSource(testProjectDir.getRoot)
    createHelloWorldJavaTestSource(testProjectDir.getRoot)

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
    assertEquals(Set("-XX:MaxMetaSpaceSize=512m", "-Xms1g", "-Xmx2g"), config.options.toSet)
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

    createHelloWorldJavaSource(testProjectDir.getRoot)
    createHelloWorldJavaTestSource(testProjectDir.getRoot)

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
    assertEquals(
      Set(
        "-XX:+UseG1GC",
        "-verbose:gc",
        "-Xms1g",
        "-Xmx2g",
        "-Dproperty=value"
      ),
      config.options.toSet
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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
         |}
      """.stripMargin
    )

    val setupA = getSetup(buildDirA)
    assertEquals(JavaThenScala, setupA.order)

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
         |  implementation 'org.scala-lang:scala-library:2.12.8'
         |}
      """.stripMargin
    )

    val setupB = getSetup(buildDirB)
    assertEquals(Mixed, setupB.order)
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
        |  implementation 'org.scala-lang:scala-library:2.12.8'
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
        |  implementation project(':c')
        |  implementation 'org.typelevel:cats-core_2.12:1.2.0'
        |  implementation project(':a')
        |  implementation project(':b')
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
        |  implementation project(':e')
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

  @Test def compilerManuallySetupPluginsGeneratedCorrectly(): Unit = {
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
        |  implementation 'org.scala-lang:scala-library:2.12.8'
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
    assert(
      resultConfig.project.`scala`.get.options
        .exists(p => p.startsWith("-Xplugin:") && p.contains("semanticdb-scalac_2.12.8"))
    )
  }

  @Test def scalaCompilerPluginsGeneratedCorrectly(): Unit = {
    if (gradleVersion >= "6.4") {
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
          |dependencies {
          |  implementation 'org.scala-lang:scala-library:2.12.8'
          |  scalaCompilerPlugins "org.scalameta:semanticdb-scalac_2.12.8:4.1.9"
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
        resultConfig.project.resolution.get.modules
          .exists(p => p.name == "semanticdb-scalac_2.12.8")
      )

      assert(
        resultConfig.project.`scala`.get.options
          .exists(p => p.startsWith("-Xplugin:") && p.contains("semanticdb-scalac_2.12.8"))
      )
    }
  }

  @Test def worksWithIntegrationTests(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDir = testProjectDir.newFolder("a")
    testProjectDir.newFolder("a", "src", "main", "scala")
    testProjectDir.newFolder("a", "src", "test", "scala")
    val buildFile = new File(buildDir, "build.gradle")

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
         |sourceSets {
         |  intTest {
         |    compileClasspath += sourceSets.main.output
         |    runtimeClasspath += sourceSets.main.output
         |  }
         |  noTests {
         |    compileClasspath += sourceSets.main.output
         |    runtimeClasspath += sourceSets.main.output
         |  }
         |}
         |
         |configurations {
         |  intTestImplementation.extendsFrom implementation
         |  intTestRuntimeOnly.extendsFrom runtimeOnly
         |  noTestsImplementation.extendsFrom implementation
         |  noTestsRuntimeOnly.extendsFrom runtimeOnly
         |}
         |
         |task integrationTest(type: Test) {
         |  testClassesDirs = sourceSets.intTest.output.classesDirs
         |}
         |
         |dependencies {
         |  implementation 'org.scala-lang:scala-library:2.12.8'
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'scala-multi-projects'
        |include 'a'
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
    val bloopA = new File(bloopDir, "a.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopAIntegTest = new File(bloopDir, "a-intTest.json")
    val bloopANoTests = new File(bloopDir, "a-noTests.json")

    val configA = readValidBloopConfig(bloopA)
    val configATest = readValidBloopConfig(bloopATest)
    val configAIntegTest = readValidBloopConfig(bloopAIntegTest)
    val configANoTests = readValidBloopConfig(bloopANoTests)

    assert(hasTag(configA, Tag.Library))
    assert(hasTag(configATest, Tag.Test))
    assert(hasTag(configAIntegTest, Tag.Test))
    assert(hasTag(configANoTests, Tag.Library))

    assert(hasBothClasspathsEntryName(configAIntegTest, "/a/build/classes"))

    assert(hasTestFramework(configAIntegTest, TestFramework.JUnit))
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
         |  implementation group: 'org.scala-lang', name: 'scala-library', version: "$version"
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

    assertEquals(List(projectName), configTestFile.project.dependencies)
    assert(hasTag(configTestFile, Tag.Test))
    assert(compileBloopProject(s"${projectName}-test", bloopDir).status.isOk)
    assertAllConfigsMatchJarNames(List(configFile, configTestFile), List("scala-library"))
  }

  private def getClasspath: java.lang.Iterable[File] = {
    new ClassGraph().getClasspathFiles()
  }

}
