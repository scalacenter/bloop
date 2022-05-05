package bloop

import scala.collection.JavaConverters._

import bloop.io.AbsolutePath
import bloop.logging.DebugFilter
import bloop.logging.Logger

import coursierapi.Repository
import coursierapi.error.CoursierError

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
      val baseDep = coursierapi.Dependency.of(organization, module, version)
      if (resolveSources) baseDep.withClassifier("sources")
      else baseDep
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
      dependencies: Seq[coursierapi.Dependency],
      logger: Logger,
      resolveSources: Boolean = false,
      additionalRepositories: Seq[Repository] = Nil
  )(implicit ec: scala.concurrent.ExecutionContext): Either[CoursierError, Array[AbsolutePath]] = {
    var fetch = coursierapi.Fetch
      .create()
      .withDependencies(dependencies: _*)
    if (resolveSources)
      fetch.addArtifactTypes("src", "jar")
    fetch.addRepositories(additionalRepositories: _*)

    try Right(fetch.fetch().asScala.toArray.map(f => AbsolutePath(f.toPath)))
    catch {
      case error: CoursierError => Left(error)
    }
  }

  def majorMinorVersion(version: String): String =
    version.reverse.dropWhile(_ != '.').tail.reverse
}
