package bloop

import bloop.logging.{ DebugFilter, Logger }
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
  import coursier.interop.scalaz._
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
    logger.debug(s"Resolving $organization:$module:$version")(DebugFilter.Compilation)
    val org = coursier.Organization(organization)
    val moduleName = coursier.ModuleName(module)
    val dependency = Dependency(Module(org, moduleName), version)
    val start = Resolution(Set(dependency))
    val repositories = {
      val baseRepositories = Seq(
        Cache.ivy2Local,
        MavenRepository("https://repo1.maven.org/maven2"),
        MavenRepository("https://dl.bintray.com/scalacenter/releases"))
      baseRepositories ++ additionalRepositories
    }
    val fetch = Fetch.from(repositories, Cache.fetch())
    val resolution = start.process.run(fetch).unsafePerformSync
    val errors = resolution.errors
    if (errors.isEmpty) {
      val localArtifacts: List[Either[FileError, File]] =
        Task.gatherUnordered(resolution.artifacts().map(Cache.file(_).run)).unsafePerformSync
      localArtifacts.collect { case Right(f) => AbsolutePath(f.toPath) }.toArray
    } else {
      val moduleInfo = s"$organization:$module:$version"
      sys.error(
        s"Resolution of module $moduleInfo failed with: ${errors.mkString("\n =>", "=> \n", "\n")}"
      )
    }
  }
}
