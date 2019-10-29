package bloop.bloopgun.core

import bloop.bloopgun.util.Feedback

import coursier.core.Repository
import coursier.error.CoursierError

import java.nio.file.Path
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
   * @param additionalRepositories Additional repositories to include in resolition.
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
   * @param additionalRepositories Additional repositories to include in resolition.
   * @return Either a coursier error or all the resolved files.
   */
  def resolveWithErrors(
      organization: String,
      module: String,
      version: String,
      logger: Logger,
      additionalRepositories: Seq[Repository] = Nil
  )(implicit ec: scala.concurrent.ExecutionContext): Either[CoursierError, Array[Path]] = {
    import coursier._
    logger.info(Feedback.resolvingDependency(s"$organization:$module:$version"))
    val org = coursier.Organization(organization)
    val moduleName = coursier.ModuleName(module)
    val dependency = Dependency.of(Module(org, moduleName), version)
    var fetch = Fetch()
      .addDependencies(dependency)
      .addRepositories(Repositories.bintray("scalacenter", "releases"))
    for (repository <- additionalRepositories) {
      fetch.addRepositories(repository)
    }

    try Right(fetch.run().map(f => f.toPath).toArray)
    catch {
      case error: CoursierError => Left(error)
    }
  }
}
