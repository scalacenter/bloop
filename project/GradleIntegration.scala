package build

import sbt.io._
import sbt.io.syntax._
import java.net.URL

import sbt.internal.util.MessageOnlyException

import scala.sys.process.Process

object GradleIntegration {
  def fetchGradleApi(version: String, targetDir: File): Unit = {
    val targetFile = targetDir / s"gradle-api-$version.jar"

    if (!targetFile.exists()) {
      val url = new URL(s"https://services.gradle.org/distributions/gradle-$version-bin.zip")
      println(s"Fetching Gradle API version $version from $url")
      IO.withTemporaryDirectory { gradleDir =>
        IO.withTemporaryDirectory { dummyProjectDir =>
          IO.unzipURL(url, gradleDir)

          IO.write(dummyProjectDir / "build.gradle",
            """apply plugin: 'java'
              |
              |dependencies {
              |  compile gradleApi()
              |}
              |
              |task("printClassPath") << {
              |  println project.sourceSets.test.runtimeClasspath.asPath
              |}
            """.stripMargin)

          val gradleExecutable: File = gradleDir / s"gradle-$version" / "bin" / "gradle"
          gradleExecutable.setExecutable(true)

          println("Running gradle...")
          val result: String = Process(Seq(gradleExecutable.getAbsolutePath, "printClassPath"), dummyProjectDir).!!
          val jars = result.split(':')
          val gradleApiJar = new File(jars.find(_.endsWith(s"gradle-api-$version.jar")).getOrElse(
            throw new MessageOnlyException(s"Could not find gradle-api artifact in the generated class path: $result")))

          println(s"Copying Gradle API from: ${gradleApiJar.getAbsolutePath} to ${targetDir.getAbsolutePath}")
          IO.copyFile(gradleApiJar, targetFile)
        }
      }
    } else {
      println(s"Gradle API already exists in ${targetFile.getAbsolutePath}")
    }
  }
}
