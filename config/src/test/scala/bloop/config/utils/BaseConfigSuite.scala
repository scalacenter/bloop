package bloop.config.utils

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import bloop.config.Config
import bloop.config.PlatformFiles

trait BaseConfigSuite {

  protected def createSource(
      projectDir: File,
      contents: String,
      sourceSetName: String,
      language: String
  ): Unit = {
    createSource(projectDir, contents, sourceSetName, "Hello", language)
  }

  private def assertTrue(clue: String, condition: Boolean) = {
    assert(condition, clue)
  }

  private def assertFalse(clue: String, condition: Boolean) = {
    assert(!condition, clue)
  }

  protected def createSource(
      projectDir: File,
      contents: String,
      sourceSetName: String,
      fileName: String,
      language: String
  ): Unit = {
    val srcDir = projectDir.toPath.resolve("src").resolve(sourceSetName).resolve(language)
    Files.createDirectories(srcDir)
    val srcFile = srcDir.resolve(s"$fileName.$language")
    Files.write(srcFile, contents.getBytes(StandardCharsets.UTF_8))
    ()
  }

  protected final val ScalaHelloWorldSource: String = {
    """
      |object Hello {
      |  def main(args: Array[String]): Unit = {
      |    println("Hello")
      |  }
      |}
    """.stripMargin
  }

  protected final val JavaHelloWorldSource: String = {
    """
      |public class Hello {
      |    public static void main(String[] args) {
      |        System.out.println("Hello World");
      |    }
      |}
    """.stripMargin
  }

  protected def createHelloWorldJavaSource(projectDir: File): Unit = {
    createSource(projectDir, JavaHelloWorldSource, "main", "java")
  }

  protected def createHelloWorldJavaTestSource(projectDir: File): Unit = {
    createSource(projectDir, JavaHelloWorldSource, "test", "java")
  }

  protected def createHelloWorldScalaTestSource(projectDir: File, source: String = ""): Unit = {
    createSource(projectDir, if (source.isEmpty) ScalaHelloWorldSource else source, "test", "scala")
  }

  protected def createHelloWorldScalaTestFixtureSource(
      projectDir: File,
      source: String = ""
  ): Unit = {
    createSource(
      projectDir,
      if (source.isEmpty) ScalaHelloWorldSource else source,
      "testFixtures",
      "scala"
    )
  }

  protected def createHelloWorldScalaSource(projectDir: File, source: String = ""): Unit = {
    createSource(projectDir, if (source.isEmpty) ScalaHelloWorldSource else source, "main", "scala")
  }

  protected def readValidBloopConfig(file: File): Config.File = {
    assertTrue(s"The bloop project file should exist: $file", file.exists())
    val bytes = Files.readAllBytes(file.toPath)
    bloop.config.read(bytes) match {
      case Right(file) => file
      case Left(failure) =>
        throw new AssertionError(s"Failed to parse ${file.getAbsolutePath}: $failure")
    }
  }

  protected def hasPathEntryName(
      entryName: String,
      paths: List[bloop.config.PlatformFiles.Path]
  ): Boolean = {
    val pathValidEntryName = entryName.replace('/', File.separatorChar)
    val pathAsStr = paths.map(_.toString)
    pathAsStr.exists(_.contains(pathValidEntryName))
  }

  protected def hasRuntimeClasspathEntryName(config: Config.File, entryName: String): Boolean = {
    config.project.platform.exists {
      case platform: Config.Platform.Jvm =>
        platform.classpath.exists(hasPathEntryName(entryName, _))
      case _ => false
    }
  }

  protected def hasCompileClasspathEntryName(config: Config.File, entryName: String): Boolean = {
    hasPathEntryName(entryName, config.project.classpath)
  }

  protected def hasBothClasspathsEntryName(config: Config.File, entryName: String): Boolean = {
    hasCompileClasspathEntryName(config, entryName) &&
    hasRuntimeClasspathEntryName(config, entryName)
  }

  protected def idxOfClasspathEntryName(config: Config.File, entryName: String): Int = {
    val pathValidEntryName = entryName.replace('/', File.separatorChar)
    config.project.classpath.takeWhile(!_.toString.contains(pathValidEntryName)).size
  }

  protected def hasTestFramework(config: Config.File, framework: Config.TestFramework): Boolean = {
    config.project.test.map(_.frameworks).getOrElse(Nil).contains(framework)
  }

  protected def hasTag(config: Config.File, tag: String): Boolean = {
    config.project.tags.getOrElse(Nil).contains(tag)
  }

  protected def writeBuildScript(buildFile: File, contents: String): Unit = {
    Files.write(buildFile.toPath, contents.getBytes(StandardCharsets.UTF_8))
    ()
  }

  protected def assertAllConfigsMatchJarNames(
      configs: List[Config.File],
      jarNames: List[String]
  ): Unit = {
    assertContainsJarNames(configs, jarNames, _.contains(_), assertTrue)
  }

  protected def assertAllConfigsHaveAllJars(
      configs: List[Config.File],
      jarNames: List[String]
  ): Unit = {
    assertContainsJarNames(configs, jarNames.map(j => s"$j.jar"), _ == _, assertTrue)
  }

  protected def assertNoConfigsHaveAnyJars(
      configs: List[Config.File],
      jarNames: List[String]
  ): Unit = {
    assertContainsJarNames(configs, jarNames.map(j => s"$j.jar"), _ == _, assertFalse)
  }

  protected def assertContainsJarNames(
      configs: List[Config.File],
      jarNames: List[String],
      matchMethod: (String, String) => Boolean,
      assertMethod: (String, Boolean) => Unit
  ): Unit = {
    def getFileName(path: bloop.config.PlatformFiles.Path): String = {
      // possibly broken in some corner cases, but should do the job in the tests here
      val pathStr = path.toString
      val slashIdx = pathStr.lastIndexOf('/')
      val bslashIdx = pathStr.lastIndexOf('\\')
      val idx = slashIdx.max(bslashIdx)
      if (idx >= 0) pathStr.drop(idx + 1)
      else pathStr
    }
    configs.foreach(config =>
      jarNames
        .foreach(jarName =>
          assertMethod(
            s"${config.project.name} $jarName",
            config.project.resolution.exists(
              _.modules.exists(
                _.artifacts.exists(a =>
                  matchMethod(PlatformFiles.getFileName(a.path).toString, jarName)
                )
              )
            )
          )
        )
    )
  }
}
