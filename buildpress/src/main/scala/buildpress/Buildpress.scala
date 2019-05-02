package buildpress

import buildpress.io.AbsolutePath

import java.nio.file.Files
import java.io.InputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.io.IOException
import scala.util.control.NonFatal
import java.nio.ByteBuffer
import scala.util.Try
import java.util.concurrent.TimeUnit
import java.net.URISyntaxException
import java.net.URI
import java.nio.file.Paths
import java.nio.file.InvalidPathException
import bloop.launcher.core.Shell
import bloop.launcher.util.Environment
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
  def exit(exitCode: Int): Unit

  import BuildpressParams.buildpressParamsParser
  implicit val messagesParams =
    Messages.messages[BuildpressParams].copy(appName = "buildpress", progName = "buildpress")
  implicit val messagesParamsHelp = messagesParams.withHelp

  def error(msg: String): String = s"❌  $msg"
  def success(msg: String): String = s"✅  $msg"
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
              }
            }
        }
    }
  }

  def press(params: BuildpressParams): Either[BuildpressError, Unit] = {
    val clonedPaths = validateBuildpressHome(params.buildpressHome).flatMap { buildpressHome =>
      val buildpressCacheDir = buildpressHome.resolve("cache")
      Files.createDirectories(buildpressCacheDir.underlying)
      val bytes = Files.readAllBytes(params.buildpressFile.underlying)
      val contents = new String(bytes, StandardCharsets.UTF_8)
      parseUris(contents).flatMap { uris =>
        val init: Either[BuildpressError, List[AbsolutePath]] = Right(Nil)
        uris.foldLeft(init) {
          case (previousResult, (repoId, repoUri)) =>
            previousResult.flatMap { clonedPaths =>
              processUri(repoId, repoUri, buildpressCacheDir).map { path =>
                path :: clonedPaths
              }
            }
        }
      }
    }

    def whenBloopDirExists[T](
        buildBaseDir: AbsolutePath
    )(processBloopDir: AbsolutePath => T): Either[BuildpressError, T] = {
      val bloopConfigDir = buildBaseDir.resolve(".bloop")
      if (bloopConfigDir.exists) Right(processBloopDir(bloopConfigDir))
      else {
        val msg = s"Missing $bloopConfigDir after build import!"
        Left(BuildpressError.ImportFailure(error(msg), None))
      }
    }

    val bloopConfigDirs = clonedPaths.flatMap { buildPaths =>
      val init: Either[BuildpressError, List[AbsolutePath]] = Right(Nil)
      buildPaths.foldLeft(init) {
        case (previousResult, buildPath) =>
          previousResult.flatMap { previousBloopDirs =>
            detectBuildTool(buildPath) match {
              case Some(sbtBuild: BuildTool.Sbt) =>
                out.println(success(s"Detected $sbtBuild"))
                exportSbtBuild(sbtBuild, params.bloopVersion).flatMap { _ =>
                  whenBloopDirExists(sbtBuild.baseDir) { bloopDir =>
                    bloopDir :: previousBloopDirs
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
      }
    }

    bloopConfigDirs.map { configDirs =>
      configDirs.foreach { configDir =>
        out.println(success(s"Generated $configDir"))
      }
      out.println(s"😎  Buildpress finished successfully")
    }
  }

  def validateBuildpressHome(
      homeDir: AbsolutePath
  ): Either[BuildpressError.InvalidBuildpressHome, AbsolutePath] = {
    if (homeDir.getParent.exists) {
      Files.createDirectories(homeDir.underlying)
      Right(homeDir)
    } else {
      // We don't create the parent of the buildpress home out of precaution
      val msg =
        s"Detected buildpress home '${homeDir.syntax}' cannot be created because its parent doesn't exist"
      Left(BuildpressError.InvalidBuildpressHome(error(msg)))
    }
  }

  def processUri(
      id: String,
      uri: URI,
      cacheDir: AbsolutePath
  ): Either[BuildpressError.CloningFailure, AbsolutePath] = {
    val scheme = uri.getScheme()
    if (scheme == "file") Right(AbsolutePath(uri))
    else {
      val supportsGit = {
        scheme == "git" ||
        ((scheme == "http" || scheme == "https") && {
          val part = uri.getRawSchemeSpecificPart()
          part != null && part.endsWith(".git")
        })
      }

      if (!supportsGit) {
        val msg = "Expected valid git reference or https to git repo"
        Left(BuildpressError.CloningFailure(error(msg), None))
      } else {
        cloneGitUri(id, uri, cacheDir)
      }
    }
  }

  def cloneGitUri(
      id: String,
      uri: URI,
      cacheDir: AbsolutePath
  ): Either[BuildpressError.CloningFailure, AbsolutePath] = {
    val supportsGit = {
      val scheme = uri.getScheme()
      scheme == "git" ||
      ((scheme == "http" || scheme == "https") && {
        val part = uri.getRawSchemeSpecificPart()
        part != null && part.endsWith(".git")
      })
    }

    if (!supportsGit) {
      val msg = "Expected valid git reference or https to git repo"
      Left(BuildpressError.CloningFailure(error(msg), None))
    } else {
      val sha = uri.getFragment()
      if (sha == null) {
        val msg =
          s"Missing sha hash in uri $uri, expected format 'git://github.com/foo/repo.git#23063e2813c81daee64d31dd7760f5a4fae392e6'"
        Left(BuildpressError.CloningFailure(error(msg), None))
      } else {
        val cloneTargetDir = cacheDir.resolve(id).resolve(sha)
        // If it exists, skip cloning, it's already cached
        if (cloneTargetDir.exists) {
          out.println(
            s"Skipping git clone for ${id}, ${cloneTargetDir.syntax} already exists"
          )
          Right(cloneTargetDir)
        } else {
          val clonedPath = cloneTargetDir.underlying
          val fragmentLessUri =
            (new URI(uri.getScheme, uri.getSchemeSpecificPart, null)).toASCIIString()
          val cloneCmd = List("git", "clone", fragmentLessUri, cloneTargetDir.syntax)
          out.println(s"Cloning ${fragmentLessUri}...")
          shell.runCommand(cloneCmd, cwd.underlying, Some(2 * 60L), Some(out)) match {
            case status if status.isOk =>
              out.println(success(s"Cloned $fragmentLessUri"))
              val checkoutCmd = List("git", "checkout", "-q", sha)
              shell.runCommand(checkoutCmd, clonedPath, Some(30L), Some(out)) match {
                case checkoutStatus if checkoutStatus.isOk => Right(cloneTargetDir)
                case failedCheckout =>
                  val checkoutMsg = s"Failed to checkout $sha in $clonedPath, git output:"
                  Left(BuildpressError.CloningFailure(error(checkoutMsg), None))
              }

            case failedClone =>
              val cloneErroMsg = s"Failed to clone $fragmentLessUri in $clonedPath"
              Left(BuildpressError.CloningFailure(error(cloneErroMsg), None))
          }
        }
      }
    }
  }

  def parseUris(contents: String): Either[BuildpressError.ParseFailure, List[(String, URI)]] = {
    val linesIterator = contents.split(System.lineSeparator()).iterator
    val parseResults = linesIterator.zipWithIndex.map {
      case (line, idx) =>
        val lineNumber = idx + 1
        line.split(",") match {
          case Array() | Array(_) =>
            val msg = "Missing comma between repo id and repo URI"
            Left(BuildpressError.ParseFailure(error(msg), None))
          case Array(untrimmedRepoId, untrimmedUri) =>
            val repoId = untrimmedRepoId.trim
            try Right(untrimmedRepoId -> new URI(untrimmedUri.trim))
            catch {
              case t: URISyntaxException =>
                val msg = s"Expected URI syntax at line $lineNumber, obtained '$untrimmedUri'"
                Left(BuildpressError.ParseFailure(error(msg), Some(t)))
            }
          case elements =>
            val msg = s"Expected buildpress line format 'id,uri', obtained '$line'"
            Left(BuildpressError.ParseFailure(error(msg), None))
        }
    }.toList

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
          error("Found ${failures.size} errors when parsing URIs")
      Left(BuildpressError.ParseFailure(completeErrorMsg, None))
    } else {
      val validationInit: Either[BuildpressError.ParseFailure, List[(String, URI)]] = Right(Nil)
      val uriEntries = parseResults.collect { case Right(uri) => uri }.toList
      val visitedIds = new mutable.HashMap[String, URI]()
      uriEntries.foldLeft(validationInit) {
        case (validatedEntries, entry @ (id, uri)) =>
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
      bloopVersion: String
  ): Either[BuildpressError, Unit] = {
    // TODO: Don't add bloop sbt plugin if build already has set it up
    def addSbtPlugin(buildpressSbtFile: AbsolutePath) = {
      val sbtFileContents =
        s"""
           |addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "$bloopVersion")
       """.stripMargin
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

    val metaProjectDir = buildTool.baseDir.resolve("project")
    val buildpressSbtFile = metaProjectDir.resolve("buildpress.sbt")
    for {
      _ <- addSbtPlugin(buildpressSbtFile)
      _ <- runBloopInstall(buildTool.baseDir)
    } yield {
      out.println(success(s"Exported ${buildTool.baseDir}"))
    }
  }
}
