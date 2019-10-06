package bloop.docs

import java.text.SimpleDateFormat
import java.util.Date

import bloop.internal.build.BuildInfo
import org.jsoup.Jsoup

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

case class Release(version: String, lastModified: Date) {
  def date: String = {
    val pattern = new SimpleDateFormat("dd MMM yyyy HH:mm")
    pattern.format(lastModified)
  }
}

object Sonatype {
  lazy val releaseBloop = Sonatype.fetchLatest("bloop-frontend_2.12", "public")
  lazy val releaseLauncher = Sonatype.fetchLatest("bloop-launcher_2.12", "public")

  // Copy-pasted from https://github.com/scalameta/metals/blob/994e5e6746ad327ce727d688ad9831e0fbb69b3f/metals-docs/src/main/scala/docs/Snapshot.scala
  lazy val current: Release = Release(BuildInfo.version, new Date())

  /** Returns the latest published snapshot release, or the current release if. */
  private def fetchLatest(artifact: String, repo: String): Release = {
    // maven-metadata.xml is consistently outdated so we scrape the "Last modified" column
    // of the HTML page that lists all snapshot releases instead.
    val doc = Jsoup
      .connect(
        s"https://oss.sonatype.org/content/repositories/$repo/ch/epfl/scala/$artifact/"
      )
      .get
    val dateTime = new SimpleDateFormat("EEE MMM d H:m:s z yyyy")
    val versions: Seq[Release] = doc.select("tr").asScala.flatMap { tr =>
      val lastModified =
        tr.select("td:nth-child(2)").text()
      val version =
        tr.select("td:nth-child(1)").text().stripSuffix("/")
      if (lastModified.nonEmpty && !version.contains("maven-metadata")) {
        val date = dateTime.parse(lastModified)
        List(Release(version, date))
      } else {
        List()
      }
    }
    versions.maxBy(_.lastModified.getTime)
  }
}
