package buildpress

import java.io.{IOException, InputStream, PrintStream}
import java.net.{URI, URISyntaxException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.control.NonFatal
import bloop.io.{AbsolutePath, Paths}
import bloop.launcher.core.Shell
import bloop.launcher.core.Shell.StatusCommand
import buildpress.RepositoryCache.RepoCacheDiff
import buildpress.io.{BuildpressPaths, SbtProjectHasher}
import buildpress.util.Traverse._
import caseapp.core.{Messages, WithHelp}

abstract class Buildpress(
    in: InputStream,
    out: PrintStream,
    err: PrintStream,
    shell: Shell,
    explicitBuildpressHome: Option[AbsolutePath],
    implicit val cwd: AbsolutePath
) {
  type EitherErrorOr[T] = Either[BuildpressError, T]
  def exit(exitCode: Int): Unit

  import BuildpressParams.buildpressParamsParser
  implicit val messagesParams =
    Messages.messages[BuildpressParams].copy(appName = "buildpress", progName = "buildpress")
  implicit val messagesParamsHelp = messagesParams.withHelp

  def run(args: Array[String]): Unit = {
    def errorAndExit(msg: String): Unit = { err.println(msg); exit(1) }

    BuildpressParams.buildpressParamsParser.withHelp.detailedParse(args) match {
      case Left(a) => errorAndExit(error(a))
      case Right((WithHelp(usage, help, result), remainingArgs, extraArgs)) =>
        if (help) out.println(messagesParams.helpMessage)
        if (usage) out.println(messagesParams.usageMessage)
        result match {
          case Left(parserError) => errorAndExit(error(parserError))
          case Right(params) =>
            if (!params.input.exists) {
              errorAndExit(s"Input path '${params.input}' doesn't exist")
            } else {
              press(params) match {
                case Right(_) => ()
                // Verbose, but we will enrich failure handling in the future, so required
                case Left(f: BuildpressError.CloningFailure) => errorAndExit(f.msg)
                case Left(f: BuildpressError.GitImportFailure) => errorAndExit(f.msg)
                case Left(f: BuildpressError.BuildImportFailure) => errorAndExit(f.msg)
                case Left(f: BuildpressError.InvalidBuildpressHome) => errorAndExit(f.msg)
                case Left(f: BuildpressError.ParseFailure) => errorAndExit(f.msg)
                case Left(f: BuildpressError.PersistFailure) => errorAndExit(f.msg)
                case Left(f: BuildpressError.CleanupFailure) => errorAndExit(f.msg)
              }
            }
        }
    }
  }

  private def press(params: BuildpressParams): EitherErrorOr[Unit] = {
    for {
      home <- validateBuildpressHome(params.buildpressHome)
      _ <- clearCacheIfShould(params, home)
      prevCache <- RepositoryCache.parse(home)
      repositories <- getRepositories(params, home, prevCache)
      freshCache = prevCache.merge(repositories)
      _ <- RepositoryCache.persist(freshCache)
      repoCacheDiff = freshCache.diff(prevCache)
      bloopConfigDirs <- installBloop(params, repositories, repoCacheDiff)
    } yield {
      out.println(s"Cache file ${freshCache.source}")
      bloopConfigDirs.foreach { configDir =>
        out.println(success(s"Generated $configDir"))
      }
      out.println(s"😎  Buildpress finished successfully")
    }
  }

  private def clearCacheIfShould(
      params: BuildpressParams,
      home: AbsolutePath
  ): Either[BuildpressError.CleanupFailure, Unit] = {
    if (params.clearRepoCache) {
      try {
        Paths.delete(RepositoryCache.repoCacheMetadataFile(home))
        Paths.delete(RepositoryCache.repoCacheDirectory(home))
        Right(())
      } catch {
        case e: Exception =>
          Left(
            BuildpressError
              .CleanupFailure(s"Failed to clear buildpress cache: ${e.getMessage}", None)
          )
      }
    } else {
      Right(())
    }
  }

  private def getRepositories(
      params: BuildpressParams,
      home: AbsolutePath,
      cache: RepositoryCache
  ): EitherErrorOr[List[ClonedRepository]] = {
    val input: AbsolutePath = params.input

    if (input.isFile) {
      parseAndCloneRepositories(input, home, cache)
    } else if (input.isDirectory) {
      val repoName: String = input.underlying.getFileName.toString
      // treat externally cloned repos as local
      val localRepoUri: URI = URI.create(s"file://${input.syntax}")
      Right(
        List(
          ClonedRepository(
            Repository(repoName, localRepoUri),
            input,
            SbtProjectHasher.hashProjectSettings(input)
          )
        )
      )
    } else {
      Left(
        BuildpressError.GitImportFailure(s"Don't know how to treat input [$input]", None)
      )
    }
  }

  private def validateBuildpressHome(
      homeDir: AbsolutePath
  ): Either[BuildpressError.InvalidBuildpressHome, AbsolutePath] = {
    if (homeDir.getParent.exists) {
      if (!homeDir.exists) Files.createDirectory(homeDir.underlying)
      Right(homeDir)
    } else {
      // We don't create the parent of the buildpress home out of precaution
      val msg =
        s"Detected buildpress home '${homeDir.syntax}' cannot be created, its parent doesn't exist"
      Left(BuildpressError.InvalidBuildpressHome(error(msg)))
    }
  }

  private def parseAndCloneRepositories(
      buildpressFile: AbsolutePath,
      buildpressHome: AbsolutePath,
      cachedRepos: RepositoryCache
  ): EitherErrorOr[List[ClonedRepository]] = {
    val buildpressCacheDir: AbsolutePath = RepositoryCache.repoCacheDirectory(buildpressHome)
    Files.createDirectories(buildpressCacheDir.underlying)
    val bytes = Files.readAllBytes(buildpressFile.underlying)
    val contents = new String(bytes, StandardCharsets.UTF_8)
    for {
      uris <- parseUris(buildpressCacheDir.syntax, contents)
      repos <- uris.eitherTraverse { repo =>
        setUpRepoContents(repo, buildpressCacheDir, cachedRepos).map { path =>
          ClonedRepository(
            repo,
            path,
            SbtProjectHasher.hashProjectSettings(path)
          )
        }
      }
    } yield {
      repos
    }
  }

  /**
   * @return list of bloop config directories in given repositories
   */
  private def installBloop(
      params: BuildpressParams,
      repositoryPaths: List[ClonedRepository],
      diff: RepoCacheDiff
  ): EitherErrorOr[List[AbsolutePath]] = {

    def handleInstallation(
        sbtBuild: BuildTool.Sbt,
        generated: Boolean
    ): Either[BuildpressError.BuildImportFailure, List[AbsolutePath]] = {
      val bloopDir: AbsolutePath = sbtBuild.baseDir.resolve(".bloop")
      if (generated && bloopDir.exists) {
        Right(List(bloopDir))
      } else if (!generated) {
        Right(Nil)
      } else {
        val msg = s"Missing $bloopDir after build import!"
        Left(BuildpressError.BuildImportFailure(error(msg), None))
      }
    }

    repositoryPaths.eitherFlatTraverse { clonedRepo =>
      if (diff.isChanged(clonedRepo.metadata)) {
        out.println("Buildpress repository needs an update")
        detectBuildTool(clonedRepo.localPath) match {
          case Some(sbtBuild: BuildTool.Sbt) =>
            out.println(success(s"Detected $sbtBuild"))
            out.println(s"Exporting build to bloop in ${clonedRepo.localPath}...")
            for {
              installed <- exportSbtBuild(sbtBuild, params)
              bloopDirs <- handleInstallation(sbtBuild, installed)
            } yield {
              bloopDirs
            }

          case Some(unsupportedBuildTool) =>
            val msg = s"Unsupported build tool $unsupportedBuildTool"
            Left(BuildpressError.BuildImportFailure(error(msg), None))

          case None =>
            val msg = s"No detected build tool in $clonedRepo"
            Left(BuildpressError.BuildImportFailure(error(msg), None))
        }
      } else {
        Right(Nil)
      }
    }
  }

  private def setUpRepoContents(
      repo: Repository,
      cacheDir: AbsolutePath,
      cachedRepos: RepositoryCache
  ): EitherErrorOr[AbsolutePath] = {
    if (repo.isLocal) {
      Right(AbsolutePath(repo.uri))
    } else if (repo.supportsGit) {
      cloneGitUri(repo, cacheDir, cachedRepos)
    } else {
      val msg = "Expected valid git reference or https to git repo"
      Left(BuildpressError.CloningFailure(error(msg), None))
    }
  }

  private def cloneGitUri(
      repo: Repository,
      cacheDir: AbsolutePath,
      cachedRepos: RepositoryCache
  ): Either[BuildpressError, AbsolutePath] = {
    val repoUriRepr: String = repo.uri.toASCIIString

    def wrap(
        before: => String,
        cmd: StatusCommand,
        onError: String => String,
        onSuccess: => String
    ): EitherErrorOr[Unit] = {
      out.println(before)
      cmd.toEither.left
        .map {
          case (_, err) =>
            BuildpressError.CloningFailure(onError(onError(err)), None)
        }
        .right
        .map(_ => out.println(success(onSuccess)))
    }

    repo.sha match {
      case None =>
        val expectedFormat =
          "git://github.com/foo/repo.git#23063e2813c81daee64d31dd7760f5a4fae392e6"
        val msg =
          s"Missing sha hash in uri $repoUriRepr, expected format '$expectedFormat'"
        Left(BuildpressError.CloningFailure(error(msg), None))

      case Some(sha) =>
        val cloneTargetDir = cacheDir.resolve(repo.id)
        val localPath: String = cloneTargetDir.syntax

        def clone(): EitherErrorOr[AbsolutePath] = {
          val clonePath: Path = cloneTargetDir.underlying
          val cloneUri: String = repo.uriWithoutSha
          val cloneCmd = List("git", "clone", cloneUri, cloneTargetDir.syntax)
          val cloneSubmoduleCmd = List("git", "submodule", "update", "--init")
          val checkoutCmd = List("git", "checkout", "-q", sha)

          for {
            _ <- wrap(
              s"Cloning $cloneUri...",
              shell.runCommand(cloneCmd, cwd.underlying, Some(4 * 60L), Some(out)),
              err => s"Failed to clone $cloneUri in $clonePath: $err",
              s"Cloned $cloneUri..."
            )

            _ <- wrap(
              s"Cloning submodules of $cloneUri...",
              shell.runCommand(cloneSubmoduleCmd, clonePath, Some(60L), Some(out)),
              err => s"Failed to clone submodules of $cloneUri: $err",
              s"Cloned submodules of $cloneUri"
            )

            _ <- wrap(
              s"Checking out $clonePath",
              shell.runCommand(checkoutCmd, clonePath, Some(30L), Some(out)),
              err => s"Failed to checkout $sha in $cloneTargetDir: $err",
              s"Checked out $clonePath"
            )
          } yield {
            cloneTargetDir
          }
        }

        def deleteCloneDir(): EitherErrorOr[Unit] = {
          try {
            BuildpressPaths.delete(cloneTargetDir)
            Right(())
          } catch {
            case t: IOException =>
              val msg = s"Failed to delete $cloneTargetDir: '${t.getMessage}'"
              Left(BuildpressError.CloningFailure(error(msg), None))
          }
        }

        def deleteAndClone(): EitherErrorOr[AbsolutePath] = {
          for {
            _ <- deleteCloneDir()
            _ <- clone()
          } yield {
            cloneTargetDir
          }
        }

        if (cloneTargetDir.exists) {
          cachedRepos.getById(repo) match {
            case None =>
              out.println(s"Deleting $localPath, missing ${repo.uriWithoutSha} in cache file")
              deleteAndClone()

            case Some(oldRepo) if oldRepo.metadata.uri == repo.uri =>
              out.println(s"Skipping git clone for ${repo.id}, $localPath exists")
              Right(cloneTargetDir)

            case Some(oldRepo) =>
              val oldRepoUri: String = oldRepo.metadata.uri.toASCIIString
              out.println(s"Deleting $localPath, uri $repoUriRepr != $oldRepoUri")
              deleteAndClone()
          }
        } else {
          clone()
        }
    }
  }

  private def parseUris(
      fromPath: String,
      contents: String
  ): Either[BuildpressError.ParseFailure, List[Repository]] = {
    val parseResults = contents.split(System.lineSeparator).zipWithIndex.flatMap {
      case (line, idx) =>
        if (line.startsWith("//")) Nil
        else {
          val lineNumber = idx + 1

          def position = s"$fromPath:$lineNumber"

          line.split(",") match {
            case Array("") => Nil
            case Array() | Array(_) =>
              val msg = s"Missing comma between repo id and repo URI at $position"
              List(Left(BuildpressError.ParseFailure(error(msg), None)))
            case Array(untrimmedRepoId, untrimmedUri) =>
              val repoId = untrimmedRepoId.trim
              try List(Right(Repository(untrimmedRepoId, new URI(untrimmedUri.trim))))
              catch {
                case t: URISyntaxException =>
                  val msg = s"Expected URI syntax at $position, obtained '$untrimmedUri'"
                  List(Left(BuildpressError.ParseFailure(error(msg), Some(t))))
              }
            case elements =>
              val msg = s"Expected buildpress line format 'id,uri' at $position, obtained '$line'"
              List(Left(BuildpressError.ParseFailure(error(msg), None)))
          }
        }
    }

    // Report failures to the user, one per one
    val failures = parseResults.collect { case Left(e) => e }
    val failureMsgs = failures.map { e =>
      val error = new StringBuilder()
      error
        .++=(e.msg)
        .++=(System.lineSeparator())
        .++=(e.cause.map(t => s"   Parse error: ${t.getMessage}").getOrElse(""))
        .mkString
    }

    if (failures.nonEmpty) {
      val completeErrorMsg =
        failureMsgs.mkString(System.lineSeparator) +
          System.lineSeparator() +
          error(s"Found ${failures.length} errors when parsing URIs")
      Left(BuildpressError.ParseFailure(completeErrorMsg, None))
    } else {
      val uriEntries = parseResults.collect { case Right(uri) => uri }.toList
      val visitedIds = new mutable.HashMap[String, URI]()
      uriEntries.eitherTraverse {
        case entry @ Repository(id, uri) =>
          visitedIds.get(id) match {
            case Some(alreadyMappedUri) =>
              val msg = s"Id '$id' is already used by URI $alreadyMappedUri"
              Left(BuildpressError.ParseFailure(error(msg), None))

            case None =>
              visitedIds += (id -> uri)
              Right(entry)
          }
      }
    }
  }

  private def detectBuildTool(baseDir: AbsolutePath): Option[BuildTool] = {
    val sbtMetaProject = baseDir.resolve("project")
    if (sbtMetaProject.exists) {
      val sbtProperties = new java.util.Properties()
      val sbtPropertiesFile = sbtMetaProject.resolve("build.properties")
      val inProperties = Files.newInputStream(sbtPropertiesFile.underlying)
      try sbtProperties.load(inProperties)
      finally inProperties.close()
      val sbtVersion = sbtProperties.getProperty("sbt.version")
      Some(BuildTool.Sbt(baseDir, sbtVersion))
    } else {
      // TODO: Support gradle, mill and maven here
      None
    }
  }

  private def exportSbtBuild(
      buildTool: BuildTool.Sbt,
      params: BuildpressParams
  ): Either[BuildpressError, Boolean] = {
    // TODO: Don't add bloop sbt plugin if build already has set it up
    def addSbtPlugin(buildpressSbtFile: AbsolutePath): EitherErrorOr[Unit] = {
      val sbtFileContents =
        s"""addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "${params.bloopVersion}")""".stripMargin
      try {
        val bytes = sbtFileContents.getBytes(StandardCharsets.UTF_8)
        Files.write(buildpressSbtFile.underlying, bytes)
        Right(())
      } catch {
        case NonFatal(t) =>
          val msg = s"Unexpected exception when writing to $buildpressSbtFile"
          Left(BuildpressError.BuildImportFailure(error(msg), Some(t)))
      }
    }

    def runBloopInstall(baseDir: AbsolutePath): EitherErrorOr[Unit] = {
      // Run bloop install for 15 minutes at maximum per project
      val cmd = List(
        "sbt",
        "-warn",
        "-J-Djline.terminal=jline.UnsupportedTerminal",
        "-J-Dsbt.log.noformat=true",
        "-J-Dfile.encoding=UTF-8",
        "bloopInstall"
      )

      val timeout = Some(15 * 60L) // Maximum wait is 15 minutes
      shell.runCommand(cmd, baseDir.underlying, timeout, Some(out)) match {
        case status if status.isOk => Right(())
        case failed =>
          val msg = s"Unexpected failure when running `${cmd.mkString(" ")}` in $baseDir"
          Left(BuildpressError.BuildImportFailure(error(msg), None))
      }
    }

    val bloopConfigDir = buildTool.baseDir.resolve(".bloop")
    val metaProjectDir = buildTool.baseDir.resolve("project")
    val buildpressSbtFile = metaProjectDir.resolve("buildpress.sbt")
    if (bloopConfigDir.exists && !params.regenerate) {
      out.println(success(s"Skipping export, ${buildTool.baseDir} exists"))
      Right(false)
    } else {
      for {
        _ <- addSbtPlugin(buildpressSbtFile)
        _ <- runBloopInstall(buildTool.baseDir)
      } yield {
        out.println(success(s"Exported ${buildTool.baseDir}"))
        true
      }
    }
  }
}
