package bloop

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.collection.JavaConverters._
import scala.util.Properties

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
  ): Array[AbsolutePath] = {
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
  ): Either[CoursierError, Array[AbsolutePath]] = {
    val dependencies = artifacts.map { artifact =>
      import artifact._
      logger.debug(s"Resolving $organization:$module:$version")(DebugFilter.All)
      val baseDep = coursierapi.Dependency.of(organization, module, version)
      if (resolveSources) baseDep.withClassifier("sources")
      else baseDep
    }
    resolveDependenciesWithErrors(dependencies, resolveSources, additionalRepositories, logger)
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
      resolveSources: Boolean = false,
      additionalRepositories: Seq[Repository] = Nil,
      logger: Logger
  ): Either[CoursierError, Array[AbsolutePath]] = {
    val fetch = coursierapi.Fetch
      .create()
      .withDependencies(dependencies: _*)
    if (resolveSources)
      fetch.addArtifactTypes("src", "jar")
    fetch.addRepositories(additionalRepositories: _*)

    try Right(fetch.fetch().asScala.toArray.map(f => AbsolutePath(f.toPath)))
    catch {
      case error: CoursierError =>
        // Try fallback for each dependency
        val fallbackJars = dependencies.flatMap(dep => fallbackDownload(dep, logger))
        if (fallbackJars.nonEmpty)
          Right(fallbackJars.map(AbsolutePath(_)).toArray)
        else
          Left(error)
    }
  }

  def majorMinorVersion(version: String): String =
    version.reverse.dropWhile(_ != '.').tail.reverse

  /**
   * There are some cases where we wouldn't be able to download
   * dependencies using coursier, in those cases we can try to use a locally
   * installed coursier to fetch the dependency.
   *
   * One potential issue is when we have credential issues
   */
  def fallbackDownload(
      dependency: coursierapi.Dependency,
      logger: Logger
  ): List[Path] = {
    val userHome: Path = Paths.get(Properties.userHome)
    /* Metals VS Code extension will download coursier for us most of the time */
    def inVsCodeMetals = {
      val cs = userHome.resolve(".metals/cs")
      val csExe = userHome.resolve(".metals/cs.exe")
      if (Files.exists(cs)) Some(cs)
      else if (Files.exists(csExe)) Some(csExe)
      else None
    }
    findInPath("cs")
      .orElse(findInPath("coursier"))
      .orElse(inVsCodeMetals) match {
      case None => Nil
      case Some(path) =>
        logger.debug(
          s"Found coursier in path under $path, using it to fetch dependency"
        )(DebugFilter.All)
        val module = dependency.getModule()
        val depString =
          s"${module.getOrganization()}:${module.getName()}:${dependency.getVersion}"
        runSync(
          List(path.toString(), "fetch", depString),
          AbsolutePath(userHome)
        ) match {
          case Some(out) =>
            val lines = out.linesIterator.toList
            val jars = lines.map(Paths.get(_))
            jars
          case None => Nil
        }
    }
  }

  private def findInPath(app: String): Option[Path] = {

    def endsWithCaseInsensitive(s: String, suffix: String): Boolean =
      s.length >= suffix.length &&
        s.regionMatches(
          true,
          s.length - suffix.length,
          suffix,
          0,
          suffix.length
        )

    val asIs = Paths.get(app)
    if (Paths.get(app).getNameCount >= 2) Some(asIs)
    else {
      def pathEntries =
        Option(System.getenv("PATH")).iterator
          .flatMap(_.split(File.pathSeparator).iterator)
      def pathExts =
        if (Properties.isWin)
          Option(System.getenv("PATHEXT")).iterator
            .flatMap(_.split(File.pathSeparator).iterator)
        else Iterator("")
      def matches = for {
        dir <- pathEntries
        ext <- pathExts
        app0 = if (endsWithCaseInsensitive(app, ext)) app else app + ext
        path = Paths.get(dir).resolve(app0)
        if Files.isExecutable(path) && !Files.isDirectory(path)
      } yield path
      matches.toStream.headOption
    }
  }

  /**
   * Runs a shell command synchronously, collects its output, and returns it as an Option[String].
   */
  private def runSync(
      command: List[String],
      workingDirectory: AbsolutePath
  ): Option[String] = {
    try {
      val pb = new ProcessBuilder(command: _*)
      pb.directory(workingDirectory.underlying.toFile)
      val process = pb.start()
      val is = process.getInputStream
      val reader = new BufferedReader(new InputStreamReader(is))
      val output = new StringBuilder
      var line: String = null
      while ({ line = reader.readLine(); line != null }) {
        output.append(line).append(System.lineSeparator())
      }
      val exitCode = process.waitFor()
      if (exitCode == 0) Some(output.toString)
      else None
    } catch {
      case _: Throwable => None
    }
  }
}
