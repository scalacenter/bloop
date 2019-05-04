package buildpress

import buildpress.io.AbsolutePath
import buildpress.io.BuildpressPaths

import bloop.launcher.core.Shell
import bloop.launcher.util.Environment

import java.nio.file.Files
import java.io.InputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.net.URISyntaxException
import java.net.URI
import java.nio.file.Paths
import java.nio.file.InvalidPathException

import scala.util.control.NonFatal
import scala.util.Try
import scala.collection.mutable

import caseapp.CaseApp
import caseapp.core.WithHelp
import caseapp.core.Messages

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
            if (!params.buildpressFile.exists) {
              errorAndExit(s"Input file '${params.buildpressFile}' doesn't exist")
            } else {
              press(params) match {
                case Right(_) => ()
                // Verbose, but we will enrich failure handling in the future, so required
                case Left(f: BuildpressError.CloningFailure) => errorAndExit(f.msg)
                case Left(f: BuildpressError.ImportFailure) => errorAndExit(f.msg)
                case Left(f: BuildpressError.InvalidBuildpressHome) => errorAndExit(f.msg)
                case Left(f: BuildpressError.ParseFailure) => errorAndExit(f.msg)
                case Left(f: BuildpressError.PersistFailure) => errorAndExit(f.msg)
              }
            }
        }
    }
  }

  def press(params: BuildpressParams): EitherErrorOr[Unit] = {
    for {
      home <- validateBuildpressHome(params.buildpressHome)
      cache <- parseRepositoryCache(home)
      clonedRepositories <- parseAndCloneRepositories(params, home, cache)
      bloopConfigDirs <- exportRepositories(params, clonedRepositories.map(_._2))
      newCache = cache.merge(clonedRepositories.map(_._1))
      _ <- RepositoryCache.persist(newCache)
    } yield {
      out.println(s"Cache file ${newCache.source}")
      bloopConfigDirs.foreach { configDir =>
        out.println(success(s"Generated $configDir"))
      }
      out.println(s"😎  Buildpress finished successfully")
    }
  }

  def validateBuildpressHome(
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

  def parseRepositoryCache(home: AbsolutePath): EitherErrorOr[RepositoryCache] = {
    val buildpressCacheFile = home.resolve("buildpress.out")
    if (!buildpressCacheFile.exists) Right(RepositoryCache.empty(buildpressCacheFile))
    else {
      val bytes = Files.readAllBytes(buildpressCacheFile.underlying)
      val contents = new String(bytes, StandardCharsets.UTF_8)
      parseUris(buildpressCacheFile.syntax, contents)
        .map(repos => RepositoryCache(buildpressCacheFile, repos))
    }
  }

  def parseAndCloneRepositories(
      params: BuildpressParams,
      buildpressHome: AbsolutePath,
      cachedRepos: RepositoryCache
  ): EitherErrorOr[List[(Repository, AbsolutePath)]] = {
    val buildpressCacheDir = buildpressHome.resolve("cache")
    Files.createDirectories(buildpressCacheDir.underlying)
    val bytes = Files.readAllBytes(params.buildpressFile.underlying)
    val contents = new String(bytes, StandardCharsets.UTF_8)
    parseUris(buildpressCacheDir.syntax, contents).flatMap { uris =>
      val init: EitherErrorOr[List[(Repository, AbsolutePath)]] = Right(Nil)
      val repositories = uris.foldLeft(init) {
        case (previousResult, repository) =>
          previousResult.flatMap { clonedPaths =>
            setUpRepoContents(repository, buildpressCacheDir, cachedRepos)
              .map(path => (repository -> path) :: clonedPaths)
          }
      }
      repositories.map(_.reverse)
    }
  }

  def exportRepositories(
      params: BuildpressParams,
      repositoryPaths: List[AbsolutePath]
  ): EitherErrorOr[List[AbsolutePath]] = {
    val init: EitherErrorOr[List[AbsolutePath]] = Right(Nil)
    repositoryPaths.foldLeft(init) {
      case (previousResult, buildPath) =>
        val configDirs = previousResult.flatMap { previousBloopDirs =>
          detectBuildTool(buildPath) match {
            case Some(sbtBuild: BuildTool.Sbt) =>
              out.println(success(s"Detected $sbtBuild"))
              out.println(s"Exporting build to bloop in ${buildPath}...")
              exportSbtBuild(sbtBuild, params.regenerate, params.bloopVersion).flatMap {
                generated =>
                  val bloopDir = sbtBuild.baseDir.resolve(".bloop")
                  if (generated && bloopDir.exists) Right(bloopDir :: previousBloopDirs)
                  else if (!generated) Right(previousBloopDirs)
                  else {
                    val msg = s"Missing $bloopDir after build import!"
                    Left(BuildpressError.ImportFailure(error(msg), None))
                  }
              }
            case Some(unsupportedBuildTool) =>
              val msg = s"Unsupported build tool $unsupportedBuildTool"
              Left(BuildpressError.ImportFailure(error(msg), None))
            case None =>
              val msg = s"No detected build tool in $buildPath"
              Left(BuildpressError.ImportFailure(error(msg), None))
          }
        }
        // Reverse because we have aggregated config dirs backwards
        configDirs.map(_.reverse)
    }
  }

  def setUpRepoContents(
      repo: Repository,
      cacheDir: AbsolutePath,
      cachedRepos: RepositoryCache
  ): Either[BuildpressError.CloningFailure, AbsolutePath] = {
    if (repo.isLocal) Right(AbsolutePath(repo.uri))
    else {
      if (!repo.supportsGit) {
        val msg = "Expected valid git reference or https to git repo"
        Left(BuildpressError.CloningFailure(error(msg), None))
      } else {
        cloneGitUri(repo, cacheDir, cachedRepos)
      }
    }
  }

  def cloneGitUri(
      repo: Repository,
      cacheDir: AbsolutePath,
      cachedRepos: RepositoryCache
  ): Either[BuildpressError.CloningFailure, AbsolutePath] = {
    repo.sha match {
      case None =>
        val msg =
          s"Missing sha hash in uri ${repo.uri.toASCIIString()}, expected format 'git://github.com/foo/repo.git#23063e2813c81daee64d31dd7760f5a4fae392e6'"
        Left(BuildpressError.CloningFailure(error(msg), None))
      case Some(sha) =>
        def clone(cloneTargetDir: AbsolutePath) = {
          val clonePath = cloneTargetDir.underlying
          val cloneUri = repo.uriWithoutSha
          val cloneCmd = List("git", "clone", cloneUri, cloneTargetDir.syntax)
          out.println(s"Cloning ${cloneUri}...")
          shell.runCommand(cloneCmd, cwd.underlying, Some(2 * 60L), Some(out)) match {
            case status if status.isOk =>
              out.println(success(s"Cloned $cloneUri"))
              val checkoutCmd = List("git", "checkout", "-q", sha)
              shell.runCommand(checkoutCmd, clonePath, Some(30L), Some(out)) match {
                case checkoutStatus if checkoutStatus.isOk => Right(cloneTargetDir)
                case failedCheckout =>
                  val checkoutMsg = s"Failed to checkout $sha in $cloneTargetDir"
                  Left(BuildpressError.CloningFailure(error(checkoutMsg), None))
              }

            case failedClone =>
              val cloneErroMsg = s"Failed to clone $cloneUri in $clonePath"
              Left(BuildpressError.CloningFailure(error(cloneErroMsg), None))
          }
        }

        def deleteCloneDir(cloneTargetDir: AbsolutePath) = {
          try {
            BuildpressPaths.delete(cloneTargetDir)
            Right(())
          } catch {
            case t: IOException =>
              val msg = s"Failed to delete $cloneTargetDir: '${t.getMessage()}'"
              Left(BuildpressError.CloningFailure(error(msg), None))
          }
        }

        val cloneTargetDir = cacheDir.resolve(repo.id)
        if (!cloneTargetDir.exists) clone(cloneTargetDir)
        else {
          cachedRepos.getCachedRepoFor(repo) match {
            case None =>
              out.println(
                s"Deleting ${cloneTargetDir.syntax}, missing ${repo.uriWithoutSha} in cache file"
              )
              deleteCloneDir(cloneTargetDir)
              clone(cloneTargetDir)
            case Some(oldRepo) =>
              if (oldRepo.uri == repo.uri) {
                out.println(s"Skipping git clone for ${repo.id}, ${cloneTargetDir.syntax} exists")
                Right(cloneTargetDir)
              } else {
                out.println(
                  s"Deleting ${cloneTargetDir.syntax}, uri ${repo.uri.toASCIIString()} != ${oldRepo.uri.toASCIIString}"
                )
                deleteCloneDir(cloneTargetDir)
                clone(cloneTargetDir)
              }
          }
        }
    }
  }

  def parseUris(
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
          error(s"Found ${failures.size} errors when parsing URIs")
      Left(BuildpressError.ParseFailure(completeErrorMsg, None))
    } else {
      val validationInit: Either[BuildpressError.ParseFailure, List[Repository]] = Right(Nil)
      val uriEntries = parseResults.collect { case Right(uri) => uri }.toList
      val visitedIds = new mutable.HashMap[String, URI]()
      val repositories = uriEntries.foldLeft(validationInit) {
        case (validatedEntries, entry @ Repository(id, uri)) =>
          validatedEntries.flatMap { entries =>
            visitedIds.get(id) match {
              case Some(alreadyMappedUri) =>
                val msg = s"Id '$id' is already used by URI ${alreadyMappedUri}"
                Left(BuildpressError.ParseFailure(error(msg), None))
              case None =>
                visitedIds.+=(id -> uri)
                Right(entry :: entries)
            }
          }
      }
      repositories.map(_.reverse)
    }
  }

  def detectBuildTool(baseDir: AbsolutePath): Option[BuildTool] = {
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

  def exportSbtBuild(
      buildTool: BuildTool.Sbt,
      regenerate: Boolean,
      bloopVersion: String
  ): Either[BuildpressError, Boolean] = {
    // TODO: Don't add bloop sbt plugin if build already has set it up
    def addSbtPlugin(buildpressSbtFile: AbsolutePath) = {
      val sbtFileContents =
        s"""addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "$bloopVersion")""".stripMargin
      try {
        val bytes = sbtFileContents.getBytes(StandardCharsets.UTF_8)
        Files.write(buildpressSbtFile.underlying, bytes)
        Right(())
      } catch {
        case NonFatal(t) =>
          val msg = s"Unexpected exception when writing to to $buildpressSbtFile"
          Left(BuildpressError.ImportFailure(error(msg), Some(t)))
      }
    }

    def runBloopInstall(baseDir: AbsolutePath) = {
      // Run bloop install for 15 minutes at maximum per project
      val cmd = List(
        "sbt",
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
          Left(BuildpressError.ImportFailure(error(msg), None))
      }
    }

    val bloopConfigDir = buildTool.baseDir.resolve(".bloop")
    val metaProjectDir = buildTool.baseDir.resolve("project")
    val buildpressSbtFile = metaProjectDir.resolve("buildpress.sbt")
    if (bloopConfigDir.exists && !regenerate) {
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
