package bloop.config

import java.nio.file.{Files, Paths}

import bloop.config.Config.{
  ClasspathOptions,
  CompileOptions,
  File,
  Java,
  Jvm,
  Project,
  Scala,
  TestOptions,
  Test => ConfigTest
}
import bloop.config.ConfigDecoders.allConfigDecoder
import bloop.config.ConfigEncoders.allConfigEncoder
import metaconfig.{Conf, Configured}
import metaconfig.typesafeconfig.typesafeConfigMetaconfigParser
import org.junit.Test
import org.junit.Assert

class JsonSpec {
  def parseConfig(config: File): Unit = {
    val jsonConfig = allConfigEncoder(config).spaces4
    val parsedEmptyConfig = allConfigDecoder.read(Conf.parseString(jsonConfig))
    allConfigDecoder.read(Conf.parseString(jsonConfig)) match {
      case Configured.Ok(parsed) =>
        // Compare stringified representation because `Array` equals uses reference equality
        Assert.assertEquals(allConfigEncoder(parsed).spaces4, jsonConfig)
      case Configured.NotOk(error) => sys.error(s"Could not parse simple config: $error")
    }
  }

  @Test def testEmptyConfigJson(): Unit = {
    parseConfig(File.empty)
  }

  @Test def testSimpleConfigJson(): Unit = {
    val workingDirectory = Paths.get(System.getProperty("user.dir"))
    val sourceFile = Files.createTempFile("Foo", ".scala")
    sourceFile.toFile.deleteOnExit()
    val scalaLibraryJar = Files.createTempFile("scala-library", ".jar")
    scalaLibraryJar.toFile.deleteOnExit()
    // This is like `target` in sbt.
    val outDir = Files.createTempFile("out", "test")
    outDir.toFile.deleteOnExit()
    val outAnalysisFile = outDir.resolve("out-analysis.bin")
    outAnalysisFile.toFile.deleteOnExit()
    val classesDir = Files.createTempFile("classes", "test")
    classesDir.toFile.deleteOnExit()

    val project = Project(
      "dummy-project",
      workingDirectory,
      Array(sourceFile),
      Array("dummy-2"),
      Array(scalaLibraryJar),
      ClasspathOptions.empty,
      CompileOptions.empty,
      classesDir,
      outDir,
      outAnalysisFile,
      Scala("org.scala-lang", "scala-compiler", "2.12.4", Array("-warn"), Array()),
      Jvm(Some(Paths.get("/usr/lib/jvm/java-8-jdk")), Array()),
      Java(Array("-version")),
      ConfigTest(Array(), TestOptions(Nil, Nil))
    )

    parseConfig(File(File.LatestVersion, project))
  }
}
