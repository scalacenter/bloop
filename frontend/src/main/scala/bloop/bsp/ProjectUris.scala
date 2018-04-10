package bloop.bsp

import java.net.URI

import bloop.Project
import bloop.engine.State
import bloop.io.AbsolutePath

import scala.util.Try

object ProjectUris {
  def getProjectDagFromUri(projectUri: String, state: State): Either[String, Option[Project]] = {
    if (projectUri.isEmpty) Left("URI cannot be empty.")
    else {
      val query = Try(new URI(projectUri).getRawQuery().split("&").map(_.split("="))).toEither
      query match {
        case Left(t) =>
          Left(s"URI '${projectUri}' has invalid format. Example: ${ProjectUris.Example}")
        case Right(parsed) =>
          parsed.headOption match {
            case Some(Array("id", projectName)) => Right(state.build.getProjectFor(projectName))
            case _ =>
              Left(s"URI '${projectUri}' has invalid format. Example: ${ProjectUris.Example}")
          }
      }
    }
  }

  private[bsp] val Example = "file://path/to/base/directory?id=projectName"
  def toUri(projectBaseDir: AbsolutePath, id: String): URI = {
    val uriSyntax = projectBaseDir.underlying.toUri
    new URI(s"${uriSyntax}?id=${id}")
  }
}
