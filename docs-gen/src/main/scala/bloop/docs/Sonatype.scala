package bloop.docs

import java.text.SimpleDateFormat
import java.util.Date

import bloop.internal.build.BuildInfo
import org.jsoup.Jsoup

import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import bloop.DependencyResolution
import bloop.logging.NoopLogger
import coursierapi.MavenRepository

case class Release(version: String, lastModified: Date) {
  def date: String = {
    val pattern = new SimpleDateFormat("dd MMM yyyy HH:mm")
    pattern.format(lastModified)
  }
}

object Sonatype {
  lazy val releaseBloop = fetchLatest("bloop-frontend_2.12")
  lazy val releaseBloopMaven = fetchLatest("bloop-maven-plugin")
  lazy val releaseRifle = fetchLatest("bloop-rifle_2.13")
  lazy val releaseBloopGradle = fetchLatest("gradle-bloop_2.13")

  /** Returns the latest published snapshot release, or the current release if. */
  private def fetchLatest(artifact: String): Release = {
    val artifacts = List(
      DependencyResolution.Artifact("ch.epfl.scala", artifact, "latest.stable")
    )
    val resolvedJars = DependencyResolution.resolve(
      artifacts,
      NoopLogger
    )

    val latestStableVersion = resolvedJars.find(_.syntax.contains(artifact)) match {
      case None => sys.error(s"Missing jar for resolved artifact '$artifact'")
      case Some(jar) =>
        val firstTry =
          jar.underlying
            .getFileName()
            .toString
            .stripSuffix(".jar")
            .stripPrefix(artifact + "-")

        if (!firstTry.endsWith("_2.12")) firstTry
        else jar.getParent.getParent.underlying.getFileName.toString
    }

    val doc = Jsoup
      .connect(
        s"https://repo1.maven.org/maven2/ch/epfl/scala/$artifact/"
      )
      .get

    val dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm")
    val releases = doc
      .select("pre")
      .asScala
      .flatMap { versionRow =>
        val elements = versionRow.getAllElements().asScala
        val nodes = versionRow.textNodes().asScala
        elements.zip(nodes).flatMap {
          case (element, node) =>
            val version = element.text().stripSuffix("/")
            if (version.startsWith("maven-metadata")) Nil
            else {
              node.text().trim().split("\\s+").init.toList match {
                case List(date, time) =>
                  try {
                    val parsedDate = dateTime.parse(s"$date $time")
                    List(Release(version, parsedDate))
                  } catch {
                    case NonFatal(_) => Nil
                  }
                case _ => Nil
              }
            }
        }
      }

    releases.filter(_.version == latestStableVersion).maxBy(_.lastModified.getTime)
  }

  lazy val current: Release = Release(BuildInfo.version, new Date())
}
