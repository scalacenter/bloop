package bloop.bsp

import java.net.URI
import java.nio.file.Path

import scala.util.Try

import ch.epfl.scala.bsp.Uri

import bloop.data.Project
import bloop.engine.State
import bloop.io.AbsolutePath

object ProjectUris {
  private val queryPrefix = "id="
  def getProjectDagFromUri(projectUri: String, state: State): Either[String, Option[Project]] = {
    if (projectUri.isEmpty) Left("URI cannot be empty.")
    else {
      Try(new URI(projectUri).getQuery()).toEither match {
        case Right(query) if query.startsWith(queryPrefix) =>
          val projectName = query.stripPrefix(queryPrefix)
          Right(state.build.getProjectFor(projectName))
        case _ =>
          Left(s"URI '${projectUri}' has invalid format. Example: ${ProjectUris.Example}")
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
      s"$queryPrefix${id}",
      existingUri.getFragment
    )
  }

  def toPath(uri: String) = {
    val existingUri = new URI(uri)
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
  def toPath(uri: Uri): Path = toPath(uri.value)

}
