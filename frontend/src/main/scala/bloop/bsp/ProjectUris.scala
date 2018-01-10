package bloop.bsp

import java.net.URI

import bloop.Project
import bloop.engine.{Dag, State}
import bloop.io.AbsolutePath

object ProjectUris {
  def getProjectDagFromUri(projectUri: String, state: State): Option[Project] = {
    if (projectUri.isEmpty) None
    else {
      val uri = new URI(projectUri)
      val query = uri.getQuery().split("&").map(_.split("="))
      query.headOption.flatMap {
        case Array("id", projectName) => state.build.getProjectFor(projectName)
        case _ => None
      }
    }
  }

  def toUri(projectBaseDir: AbsolutePath, id: String): URI = {
    new URI(s"file:///${projectBaseDir.syntax}?id=${id}")
  }
}
