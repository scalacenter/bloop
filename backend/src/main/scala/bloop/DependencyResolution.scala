package bloop

import bloop.logging.{DebugFilter, Logger}
import bloop.io.AbsolutePath

import sbt.librarymanagement._
import sbt.librarymanagement.ivy._
import coursier.core.Repository
import coursier.error.CoursierError
import coursier.{Dependency, Attributes, Type, Classifier, Module, Fetch, Repositories}

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
      resolveSources: Boolean = false,
      additionalRepos: Seq[Repository] = Nil
  )(implicit ec: scala.concurrent.ExecutionContext): Array[AbsolutePath] = {
    resolveWithErrors(organization, module, version, logger, resolveSources, additionalRepos) match {
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
      resolveSources: Boolean = false,
      additionalRepositories: Seq[Repository] = Nil
  )(implicit ec: scala.concurrent.ExecutionContext): Either[CoursierError, Array[AbsolutePath]] = {
    logger.debug(s"Resolving $organization:$module:$version")(DebugFilter.All)
    val org = coursier.Organization(organization)
    val moduleName = coursier.ModuleName(module)
    val attributes =
      if (!resolveSources) Attributes() else Attributes(Type.empty, Classifier.sources)
    val dependency = Dependency.of(Module(org, moduleName), version).withAttributes(attributes)
    resolveDependenciesWithErrors(List(dependency), logger, resolveSources, additionalRepositories)
  }

  /**
   * Resolve the specified dependencies and get all the files. By default, the
   * local ivy repository and Maven Central are included in resolution. This
   * resolution is pure and returns either some errors or some resolved jars.
   *
   * @param dependencies           Dependencies to resolve.
   * @param logger                 A logger that receives messages about resolution.
   * @param additionalRepositories Additional repositories to include in resolution.
   * @return Either a coursier error or all the resolved files.
   */
  def resolveDependenciesWithErrors(
      dependencies: Seq[Dependency],
      logger: Logger,
      resolveSources: Boolean = false,
      additionalRepositories: Seq[Repository] = Nil
  )(implicit ec: scala.concurrent.ExecutionContext): Either[CoursierError, Array[AbsolutePath]] = {
    var fetch = Fetch()
      .withDependencies(dependencies)
      .addRepositories(Repositories.bintray("scalacenter", "releases"))
    if (resolveSources) {
      fetch = fetch.addArtifactTypes(Type.source, Type.jar)
    }
    for (repository <- additionalRepositories) {
      fetch = fetch.addRepositories(repository)
    }

    try Right(fetch.run().map(f => AbsolutePath(f.toPath)).toArray)
    catch {
      case error: CoursierError => Left(error)
    }
  }
}
