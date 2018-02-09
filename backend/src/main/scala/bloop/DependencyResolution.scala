package bloop

import bloop.logging.Logger
import bloop.io.AbsolutePath

import sbt.librarymanagement._
import sbt.librarymanagement.ivy._

object DependencyResolution {
  private final val BloopResolvers =
    Vector(Resolver.defaultLocal, Resolver.mavenCentral, Resolver.sonatypeRepo("staging"))
  private[bloop] def getEngine(userResolvers: List[Resolver]): DependencyResolution = {
    val resolvers = if (userResolvers.isEmpty) BloopResolvers else userResolvers.toVector
    val configuration = InlineIvyConfiguration().withResolvers(resolvers)
    IvyDependencyResolution(configuration)
  }

  import java.io.File
  import coursier.{
    Cache,
    Dependency,
    FileError,
    Fetch,
    MavenRepository,
    Module,
    Repository,
    Resolution
  }
  import scalaz.\/
  import scalaz.concurrent.Task

  /**
   * Resolve the specified module and get all the files. By default, the local ivy
   * repository and Maven Central are included in resolution.
   *
   * @param organization           The module's organization.
   * @param module                 The module's name.
   * @param version                The module's version.
   * @param logger                 A logger that receives messages about resolution.
   * @param additionalRepositories Additional repositories to include in resolition.
   * @return All the files that compose the module and that could be found.
   */
  def resolve(organization: String,
              module: String,
              version: String,
              logger: Logger,
              additionalRepositories: Seq[Repository] = Nil): Array[AbsolutePath] = {
    logger.debug(s"Resolving $organization:$module:$version")
    val dependency = Dependency(Module(organization, module), version)
    val start = Resolution(Set(dependency))
    val repositories = {
      val baseRepositories = Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2"))
      baseRepositories ++ additionalRepositories
    }
    val fetch = Fetch.from(repositories, Cache.fetch())
    val resolution = start.process.run(fetch).unsafePerformSync
    // TODO: Do something with the errors.
    //val errors: Seq[((Module, String), Seq[String])] = resolution.metadataErrors
    val localArtifacts: Seq[FileError \/ File] =
      Task.gatherUnordered(resolution.artifacts.map(Cache.file(_).run)).unsafePerformSync
    val allFiles = localArtifacts.flatMap(_.toList).toArray
    allFiles.map(f => AbsolutePath(f.toPath))
  }
}
