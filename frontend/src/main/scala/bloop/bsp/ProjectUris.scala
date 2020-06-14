package bloop.bsp

import java.net.URI
import java.nio.file.Path

import bloop.data.Project
import bloop.engine.State
import bloop.io.AbsolutePath
import ch.epfl.scala.bsp.Uri

import scala.util.Try

case class ParsedProjectUri(
    path: AbsolutePath,
    name: String
)

object ProjectUris {

  def parseUri(projectUri: String): Either[String, ParsedProjectUri] = {

    def invalidFormatMessage: String =
      s"URI '${projectUri}' has invalid format. Example: ${ProjectUris.Example}"

    def findName(uri: URI): Either[String, String] = {
      val kv = uri.getRawQuery.split("&")
      val maybeName = kv
        .map(_.split("="))
        .find {
          case Array("id", _) => true
          case _ => false
        }
        .map(arr => arr(1))

      maybeName match {
        case Some(name) => Right(name)
        case None => Left(invalidFormatMessage)
      }
    }

    if (projectUri.isEmpty) Left("URI cannot be empty.")
    else {
      Try(new URI(projectUri)).toEither.left
        .map(_ => invalidFormatMessage)
        .flatMap(
          uri =>
            findName(uri)
              .map(name => ParsedProjectUri(AbsolutePath.completelyUnsafe(uri.getPath), name))
        )
    }
  }

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
