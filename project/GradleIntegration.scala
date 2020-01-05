package build

import sbt.io._
import sbt.io.syntax._
import java.net.URL

import sbt.internal.util.MessageOnlyException
import sbt.util.Logger

import scala.sys.process.Process

object GradleIntegration {
  private final val isWindows = scala.util.Properties.isWin
  def fetchGradleApi(version: String, libDir: File, logger: Logger): Unit = {
    val targetApi = libDir / s"gradle-api-$version.jar"
    val targetTestKit = libDir / s"gradle-test-kit-$version.jar"
    if (!targetApi.exists() || !targetTestKit.exists()) {
      // This is one of these things that may be outdated if the whole process fails
      val url = new URL(s"https://services.gradle.org/distributions/gradle-$version-bin.zip")
      logger.info(s"Fetching Gradle API version $version from $url (may take a while...)")
      IO.withTemporaryDirectory { gradleDir =>
        IO.withTemporaryDirectory { dummyProjectDir =>
          // Unzip and write a dummy plugin definition to force Gradle to extract the plugin api
          IO.unzipURL(url, gradleDir)
          IO.write(dummyProjectDir / "build.gradle", DummyGradlePluginDefinition)

          val gradleExecutable: File = gradleDir / s"gradle-$version" / "bin" / "gradle"
          gradleExecutable.setExecutable(true)
          val gradlePath = gradleExecutable.getAbsolutePath

          logger.info("Extracting the api path from gradle...")
          val cmdBase =
            if (isWindows) "cmd.exe" :: "/C" :: s"${gradlePath}.bat" :: Nil else gradlePath :: Nil
          val gradleCmd = cmdBase ++ Seq(
            "--stacktrace",
            "--no-daemon",
            "--console=plain",
            "--quiet",
            "printClassPath"
          )
          val result: String = Process(gradleCmd, dummyProjectDir).!!
          // Gradle returns classpath plus linefeeds and logging so parse
          val paths = result
            .split(java.io.File.pathSeparator)
            .flatMap(_.split("\\r?\\n"))
          copyGeneratedArtifact(logger, libDir, targetApi, paths, "gradle-api", version)
          copyGeneratedArtifact(logger, libDir, targetTestKit, paths, "gradle-test-kit", version)
        }
      }
    } else {
      logger.debug(s"Gradle API already exists in ${targetApi.getAbsolutePath}")
      logger.debug(s"Gradle TestKit already exists in ${targetTestKit.getAbsolutePath}")
    }
  }

  private def copyGeneratedArtifact(
      logger: Logger,
      libDir: File,
      targetFile: File,
      paths: Array[String],
      name: String,
      version: String
  ): Unit = {
    paths.find(_.endsWith(s"$name-$version.jar")) match {
      case Some(gradleApi) =>
        // Copy the api to the lib jar so that it's accessible for the compiler
        val gradleApiJar = new File(gradleApi)
        logger.info(s"Copying ${gradleApiJar.getAbsolutePath} -> ${libDir.getAbsolutePath}")
        IO.copyFile(gradleApiJar, targetFile)
      case None =>
        throw new MessageOnlyException(
          s"Fatal: could not find $name artifact in the generated class path [${paths.mkString(",")}]"
        )
    }
  }

  private final val DummyGradlePluginDefinition = {
    """apply plugin: 'java'
      |
      |dependencies {
      |  compile gradleApi()
      |  testCompile gradleTestKit()
      |}
      |
      |task("printClassPath") {
      |  println project.sourceSets.test.runtimeClasspath.asPath
      |}
    """.stripMargin
  }
}
