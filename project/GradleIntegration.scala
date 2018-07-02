package build

import sbt.io._
import sbt.io.syntax._
import java.net.URL

import sbt.internal.util.MessageOnlyException
import sbt.util.Logger

import scala.sys.process.Process

object GradleIntegration {
  def fetchGradleApi(version: String, libDir: File, logger: Logger): Unit = {
    val targetFile = libDir / s"gradle-api-$version.jar"
    if (!targetFile.exists()) {
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

          logger.info("Extracting the api path from gradle...")
          val gradleCmd = Seq(gradleExecutable.getAbsolutePath, "--stacktrace", "printClassPath")
          val result: String = Process(gradleCmd, dummyProjectDir).!!

          result.split(':').find(_.endsWith(s"gradle-api-$version.jar")) match {
            case Some(gradleApi) =>
              // Copy the api to the lib jar so that it's accessible for the compiler
              val gradleApiJar = new File(gradleApi)
              logger.info(
                s"Copying Gradle API ${gradleApiJar.getAbsolutePath} -> ${libDir.getAbsolutePath}")
              IO.copyFile(gradleApiJar, targetFile)
            case None =>
              throw new MessageOnlyException(
                s"Fatal: could not find gradle-api artifact in the generated class path $result")
          }
        }
      }
    } else {
      logger.debug(s"Gradle API already exists in ${targetFile.getAbsolutePath}")
    }
  }

  private final val DummyGradlePluginDefinition = {
    """apply plugin: 'java'
      |
      |dependencies {
      |  compile gradleApi()
      |}
      |
      |task("printClassPath") << {
      |  println project.sourceSets.test.runtimeClasspath.asPath
      |}
    """.stripMargin
  }
}
