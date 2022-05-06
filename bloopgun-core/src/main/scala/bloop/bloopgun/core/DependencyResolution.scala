package bloop.bloopgun.core

import java.nio.file.Path

import scala.collection.JavaConverters._

import bloop.bloopgun.util.Feedback

import coursierapi.Repository
import coursierapi.error.CoursierError
import snailgun.logging.Logger

object DependencyResolution {

  /**
   * Resolve the specified module and get all the files. By default, the local ivy
   * repository and Maven Central are included in resolution. This resolution throws
   * in case there is an error.
   *
   * @param organization           The module's organization.
   * @param module                 The module's name.
   * @param version                The module's version.
   * @param logger                 A logger that receives messages about resolution.
   * @param additionalRepositories Additional repositories to include in resolution.
   * @return All the resolved files.
   */
  def resolve(
      organization: String,
      module: String,
      version: String,
      logger: Logger,
      additionalRepos: Seq[Repository] = Nil
  )(implicit ec: scala.concurrent.ExecutionContext): Array[Path] = {
    resolveWithErrors(organization, module, version, logger, additionalRepos) match {
      case Right(paths) => paths
      case Left(error) => throw error
    }
  }

  /**
   * Resolve the specified module and get all the files. By default, the local ivy
   * repository and Maven Central are included in resolution. This resolution is
   * pure and returns either some errors or some resolved jars.
   *
   * @param organization           The module's organization.
   * @param module                 The module's name.
   * @param version                The module's version.
   * @param logger                 A logger that receives messages about resolution.
   * @param additionalRepositories Additional repositories to include in resolution.
   * @return Either a coursier error or all the resolved files.
   */
  def resolveWithErrors(
      organization: String,
      module: String,
      version: String,
      logger: Logger,
      additionalRepositories: Seq[Repository] = Nil
  )(implicit ec: scala.concurrent.ExecutionContext): Either[CoursierError, Array[Path]] = {
    logger.info(Feedback.resolvingDependency(s"$organization:$module:$version"))
    val dependency = coursierapi.Dependency.of(organization, module, version)
    val fetch = coursierapi.Fetch
      .create()
      .addDependencies(dependency)
    fetch.addRepositories(additionalRepositories: _*)

    try Right(fetch.fetch().asScala.toArray.map(f => f.toPath))
    catch {
      case error: coursierapi.error.CoursierError => Left(error)
    }
  }
}
