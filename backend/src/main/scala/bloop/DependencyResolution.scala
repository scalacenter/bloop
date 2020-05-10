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
   * @param organization The module's organization.
   * @param module       The module's name.
   * @param version      The module's version.
   */
  case class Artifact(organization: String, module: String, version: String)

  /**
   * Resolve the specified modules and get all the files. By default, the local Ivy
   * repository and Maven Central are included in resolution. This resolution throws
   * in case there is an error.
   *
   * @param artifacts       Artifacts to resolve
   * @param logger          A logger that receives messages about resolution.
   * @param resolveSources  Resolve JAR files containing sources
   * @param additionalRepos Additional repositories to include in resolution.
   * @return All the resolved files.
   */
  def resolve(
      artifacts: List[Artifact],
      logger: Logger,
      resolveSources: Boolean = false,
      additionalRepos: Seq[Repository] = Nil
  )(implicit ec: scala.concurrent.ExecutionContext): Array[AbsolutePath] = {
    resolveWithErrors(artifacts, logger, resolveSources, additionalRepos) match {
      case Right(paths) => paths
      case Left(error) => throw error
    }
  }

  /**
   * Resolve the specified module and get all the files. By default, the local ivy
   * repository and Maven Central are included in resolution. This resolution is
   * pure and returns either some errors or some resolved jars.
   *
   * @param artifacts Artifacts to resolve
   * @return Either a coursier error or all the resolved files.
   */
  def resolveWithErrors(
      artifacts: List[Artifact],
      logger: Logger,
      resolveSources: Boolean = false,
      additionalRepositories: Seq[Repository] = Nil
  )(implicit ec: scala.concurrent.ExecutionContext): Either[CoursierError, Array[AbsolutePath]] = {
    val dependencies = artifacts.map { artifact =>
      import artifact._
      logger.debug(s"Resolving $organization:$module:$version")(DebugFilter.All)
      val org = coursier.Organization(organization)
      val moduleName = coursier.ModuleName(module)
      val attributes =
        if (!resolveSources) Attributes() else Attributes(Type.empty, Classifier.sources)
      Dependency(Module(org, moduleName), version).withAttributes(attributes)
    }
    resolveDependenciesWithErrors(dependencies, logger, resolveSources, additionalRepositories)
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

  def majorMinorVersion(version: String): String =
    version.reverse.dropWhile(_ != '.').tail.reverse
}
