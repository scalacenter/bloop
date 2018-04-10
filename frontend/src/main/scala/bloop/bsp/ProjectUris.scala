package bloop.bsp

import java.net.URI

import bloop.Project
import bloop.engine.State
import bloop.io.AbsolutePath

import scala.util.Try

object ProjectUris {
  def getProjectDagFromUri(projectUri: String, state: State): Try[Option[Project]] = {
    val uri = new URI(projectUri)
    val query = Try(uri.getRawQuery().split("&").map(_.split("=")))
    query.map {
      _.headOption.flatMap {
        case Array("id", projectName) => state.build.getProjectFor(projectName)
        case _ => None
      }
    }
  }

  private[bsp] val Example = "file://path/to/base/directory?id=projectName"
  def toUri(projectBaseDir: AbsolutePath, id: String): URI = {
    new URI(s"file://${projectBaseDir.syntax}?id=${id}")
  }
}
