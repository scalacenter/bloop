package bloop

import bloop.logging.{DebugFilter, Logger}
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
  def resolve(
      organization: String,
      module: String,
      version: String,
      logger: Logger,
      additionalRepositories: Seq[Repository] = Nil
  )(implicit ec: scala.concurrent.ExecutionContext): Array[AbsolutePath] = {
    import coursier._
    import coursier.util.{Gather, Task}
    logger.debug(s"Resolving $organization:$module:$version")(DebugFilter.Compilation)
    val org = coursier.Organization(organization)
    val moduleName = coursier.ModuleName(module)
    val dependency = Dependency(Module(org, moduleName), version)
    val start = Resolution(Set(dependency))
    val repositories = {
      val baseRepositories = Seq(
        Cache.ivy2Local,
        MavenRepository("https://repo1.maven.org/maven2"),
        MavenRepository("https://dl.bintray.com/scalacenter/releases")
      )
      baseRepositories ++ additionalRepositories
    }
    val fetch = Fetch.from(repositories, Cache.fetch[Task]())
    val resolution = start.process.run(fetch).unsafeRun()
    val localArtifacts: Seq[(Boolean, Either[FileError, File])] = {
      Gather[Task]
        .gather(resolution.artifacts().map { artifact =>
          Cache.file[Task](artifact).run.map(artifact.optional -> _)
        })
        .unsafeRun()
    }

    val fileErrors = localArtifacts.collect {
      case (isOptional, Left(error)) if !isOptional || !error.notFound => error
    }
    if (fileErrors.isEmpty) {
      localArtifacts.collect { case (_, Right(f)) => AbsolutePath(f.toPath) }.toArray
    } else {
      val moduleInfo = s"$organization:$module:$version"
      val prettyFileErrors = fileErrors.map(_.describe).mkString(System.lineSeparator)
      sys.error(
        s"Resolution of module $moduleInfo failed with:${System.lineSeparator}${prettyFileErrors}"
      )
    }
  }
}
