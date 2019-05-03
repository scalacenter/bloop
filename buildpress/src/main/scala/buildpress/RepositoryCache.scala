package buildpress

import buildpress.io.AbsolutePath
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.io.IOException
import scala.collection.mutable

final case class RepositoryCache(source: AbsolutePath, repositories: List[Repository]) {
  def getCachedRepoFor(target: Repository): Option[Repository] =
    repositories.find(_.id == target.id)
  def merge(newRepositories: List[Repository]): RepositoryCache = {
    val mergedRepositories = new mutable.ListBuffer[Repository]
    mergedRepositories.++=(newRepositories)
    repositories.map { repository =>
      if (newRepositories.exists(_.id == repository.id)) ()
      else mergedRepositories.+=(repository)
    }
    RepositoryCache(source, mergedRepositories.toList)
  }
}

object RepositoryCache {
  def empty(source: AbsolutePath): RepositoryCache = RepositoryCache(source, Nil)

  def persist(cache: RepositoryCache): Either[BuildpressError.PersistFailure, Unit] = {
    val cacheFileContents = new StringBuilder()
    cache.repositories.foreach { repo =>
      cacheFileContents
        .++=(repo.id)
        .++=(",")
        .++=(repo.uri.toASCIIString())
        .++=(System.lineSeparator())
    }
    try {
      Files.write(
        cache.source.underlying,
        cacheFileContents.mkString.getBytes(StandardCharsets.UTF_8)
      )
      Right(())
    } catch {
      case t: IOException =>
        val msg = s"Unexpected error when persisting cache file ${cache.source}: '${t.getMessage}'"
        Left(BuildpressError.PersistFailure(error(msg), Some(t)))
    }
  }
}
