package bloop.bsp

import java.net.URI
import java.nio.file.Path

import bloop.data.Project
import bloop.engine.State
import bloop.io.AbsolutePath
import ch.epfl.scala.bsp.Uri

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

  private[bsp] val Example = "file:///path/to/base/directory?id=projectName"
  def toURI(projectBaseDir: AbsolutePath, id: String): URI = {
    // This is the "idiomatic" way of adding a query to a URI in Java
    val existingUri = projectBaseDir.underlying.toUri
    new URI(
      existingUri.getScheme,
      existingUri.getUserInfo,
      existingUri.getHost,
      existingUri.getPort,
      existingUri.getPath,
      s"id=${id}",
      existingUri.getFragment
    )
  }

  def toPath(uri: Uri): Path = {
    val existingUri = new URI(uri.value)
    val uriWithNoQuery = new URI(
      existingUri.getScheme,
      existingUri.getUserInfo,
      existingUri.getHost,
      existingUri.getPort,
      existingUri.getPath,
      null,
      existingUri.getFragment
    )

    java.nio.file.Paths.get(uriWithNoQuery)
  }
}
