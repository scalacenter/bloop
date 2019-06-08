package buildpress

import java.io.IOException
import buildpress.RepositoryCache.RepoCacheDiff
import buildpress.config.Config.{RepoCacheEntries, RepoCacheEntry, RepoCacheFile}
import buildpress.io.AbsolutePath

final case class RepositoryCache(source: AbsolutePath, repositories: List[ClonedRepository]) {
  private lazy val lookupById: Map[String, ClonedRepository] =
    repositories.map(r => r.metadata.id -> r)(collection.breakOut)

  def getById(target: Repository): Option[ClonedRepository] =
    lookupById.get(target.id)

  def merge(newRepositories: List[ClonedRepository]): RepositoryCache = {
    val mergedRepositories = List.newBuilder[ClonedRepository]
    mergedRepositories ++= newRepositories
    mergedRepositories ++= repositories.iterator
      .filterNot(r => newRepositories.exists(_.metadata.id == r.metadata.id))
    RepositoryCache(source, mergedRepositories.result())
  }

  def diff(prev: RepositoryCache): RepoCacheDiff = new RepoCacheDiff(prev, this)
}

object RepositoryCache {
  final class RepoCacheDiff(prev: RepositoryCache, curr: RepositoryCache) {
    private lazy val symDiff: Set[Repository] = {
      def diff(left: RepositoryCache, right: RepositoryCache): Set[Repository] = {
        left.repositories.flatMap { leftRepo =>
          right.getById(leftRepo.metadata) match {
            case Some(rightRepo) =>
              if (rightRepo.buildSettingsHashes == leftRepo.buildSettingsHashes) {
                Nil
              } else {
                List(leftRepo.metadata)
              }
            case None =>
              List(leftRepo.metadata)
          }
        }(collection.breakOut)
      }
      diff(prev, curr) ++ diff(curr, prev)
    }

    def isChanged(repo: Repository): Boolean = symDiff.contains(repo)
  }

  def empty(source: AbsolutePath): RepositoryCache = RepositoryCache(source, Nil)

  def persist(cache: RepositoryCache): Either[BuildpressError.PersistFailure, Unit] = {
    val repr = RepoCacheFile(
      RepoCacheFile.LatestVersion,
      RepoCacheEntries(
        cache.repositories.map { repo =>
          RepoCacheEntry(
            repo.metadata.id,
            repo.metadata.uri,
            repo.localPath.underlying,
            repo.buildSettingsHashes
          )
        }
      )
    )
    try {
      Right(buildpress.config.Config.write(repr, cache.source.underlying))
    } catch {
      case t: IOException =>
        val msg = s"Unexpected error when persisting cache file ${cache.source}: '${t.getMessage}'"
        Left(BuildpressError.PersistFailure(error(msg), Some(t)))
    }
  }

  def repoCacheMetadataFile(home: AbsolutePath): AbsolutePath = {
    home.resolve("buildpress-repo-cache.json")
  }

  def repoCacheDirectory(home: AbsolutePath): AbsolutePath = {
    home.resolve("cache")
  }

  def parse(home: AbsolutePath): Either[BuildpressError.ParseFailure, RepositoryCache] = {
    val buildpressCacheFile: AbsolutePath = repoCacheMetadataFile(home)
    if (buildpressCacheFile.exists) {
      buildpress.config.Config.readBuildpressConfig(buildpressCacheFile.underlying) match {
        case Left(err) =>
          Left(BuildpressError.ParseFailure(err, None))
        case Right(cf) =>
          Right(
            RepositoryCache(
              buildpressCacheFile,
              cf.cache.repos.map { rce =>
                ClonedRepository(
                  Repository(
                    rce.id,
                    rce.uri
                  ),
                  AbsolutePath(rce.localPath),
                  rce.hashes
                )
              }
            )
          )
      }
    } else {
      Right(RepositoryCache.empty(buildpressCacheFile))
    }
  }
}
