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
    pluginSource: BuildpressPluginSource,
    in: InputStream,
    out: PrintStream,
    err: PrintStream,
    shell: Shell,
    explicitBuildpressHome: Option[AbsolutePath],
    implicit val cwd: AbsolutePath
) {
  import BuildpressParams.buildpressParamsParser
  implicit val messagesParams =
    Messages.messages[BuildpressParams].copy(appName = "buildpress", progName = "buildpress")
  implicit val messagesParamsHelp = messagesParams.withHelp
  def exit(exitCode: Int): Unit
  def run(args: Array[String]): Unit = {
    def errorAndExit(errorMsg: String): Unit = { err.println(errorMsg); exit(1) }

    BuildpressParams.buildpressParamsParser.withHelp.detailedParse(args) match {
      case Left(a) => errorAndExit(s"❌ $a")
      case Right((WithHelp(usage, help, result), remainingArgs, extraArgs)) =>
        if (help) out.println(messagesParams.helpMessage)
        if (usage) out.println(messagesParams.usageMessage)
        result match {
          case Left(parserError) => errorAndExit(s"❌ $parserError")
          case Right(params) =>
            if (!params.buildpressFile.exists) {
              errorAndExit(s"❌ Input file '${params.buildpressFile}' doesn't exist")
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

  def findBuildpressHome: Either[BuildpressError.InvalidBuildpressHome, AbsolutePath] = {
    explicitBuildpressHome match {
      case Some(buildpressHome) => Right(buildpressHome)
      case None =>
        val processUserBuildpress = Option(System.getProperty("buildpress.home")).map {
          userBuildpressHomePath =>
            try {
              val userBuildpressHome = AbsolutePath(userBuildpressHomePath)
              if (userBuildpressHome.getParent.exists) {
                Files.createDirectories(userBuildpressHome.underlying)
                Right(userBuildpressHome)
              } else {
                // We don't create the parent of the buildpress home out of precaution
                val errorMsg =
                  s"❌ Detected buildpress home '${userBuildpressHome.syntax}' cannot be created because its parent doesn't exist"
                Left(BuildpressError.InvalidBuildpressHome(errorMsg))
              }
            } catch {
              case t: InvalidPathException =>
                val errorMsg =
                  s"❌ Unexpected error parsing '${userBuildpressHomePath}': ${t.getMessage()}"
                Left(BuildpressError.InvalidBuildpressHome(errorMsg))
            }
        }

        processUserBuildpress.getOrElse {
          val msg = "❌ Missing home for buildpress, pass it via `-Dbuildpress.home=`"
          Left(BuildpressError.InvalidBuildpressHome(msg))
        }
    }
  }

  def press(params: BuildpressParams): Either[BuildpressError, Unit] = {
    val clonedPaths = findBuildpressHome.flatMap { buildpressHome =>
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
        val msg = s"❌ Missing $bloopConfigDir after build import!"
        Left(BuildpressError.ImportFailure(msg, None))
      }
    }

    val bloopConfigDirs = clonedPaths.flatMap { buildPaths =>
      val init: Either[BuildpressError, List[AbsolutePath]] = Right(Nil)
      buildPaths.foldLeft(init) {
        case (previousResult, buildPath) =>
          previousResult.flatMap { previousBloopDirs =>
            detectBuildTool(buildPath) match {
              case Some(sbtBuild: BuildTool.Sbt) =>
                out.println(s"✅ Detected $sbtBuild")
                exportSbtBuild(sbtBuild, params.bloopVersion).flatMap { _ =>
                  whenBloopDirExists(sbtBuild.baseDir) { bloopDir =>
                    bloopDir :: previousBloopDirs
                  }
                }
              case Some(unsupportedBuildTool) =>
                val msg = s"❌ Unsupported build tool $unsupportedBuildTool"
                Left(BuildpressError.ImportFailure(msg, None))
              case None =>
                val msg = s"❌ No detected build tool in $buildPath"
                Left(BuildpressError.ImportFailure(msg, None))
            }
          }
      }
    }

    bloopConfigDirs.map { configDirs =>
      configDirs.foreach { configDir =>
        out.println(s"✅ Generated $configDir")
      }
      out.println(s"😎 Buildpress finished successfully")
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
        val msg = "❌ Expected valid git reference or https to git repo"
        Left(BuildpressError.CloningFailure(msg, None))
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
      Left(
        BuildpressError.CloningFailure("❌ Expected valid git reference or https to git repo", None)
      )
    } else {
      val sha = uri.getFragment()
      if (sha == null) {
        val msg =
          s"❌ Missing sha hash in uri $uri, expected format 'git://github.com/foo/repo.git#23063e2813c81daee64d31dd7760f5a4fae392e6'"
        Left(BuildpressError.CloningFailure(msg, None))
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
              out.println(s"✅ Cloned $fragmentLessUri")
              val checkoutCmd = List("git", "checkout", "-q", sha)
              shell.runCommand(checkoutCmd, clonedPath, Some(30L), Some(out)) match {
                case checkoutStatus if checkoutStatus.isOk => Right(cloneTargetDir)
                case failedCheckout =>
                  val checkoutMsg = s"❌ Failed to checkout $sha in $clonedPath, git output:"
                  Left(BuildpressError.CloningFailure(checkoutMsg, None))
              }

            case failedClone =>
              val cloneErroMsg = s"❌ Failed to clone $fragmentLessUri in $clonedPath"
              Left(BuildpressError.CloningFailure(cloneErroMsg, None))
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
            val msg = "❌ Expected buildpress line format 'id,uri'"
            Left(BuildpressError.ParseFailure(msg, None))
          case Array(untrimmedRepoId, untrimmedUri) =>
            val repoId = untrimmedRepoId.trim
            try Right(untrimmedRepoId -> new URI(untrimmedUri.trim))
            catch {
              case t: URISyntaxException =>
                val msg = s"❌ Expected URI syntax at line $lineNumber, obtained '$untrimmedUri'"
                Left(BuildpressError.ParseFailure(msg, Some(t)))
            }
          case elements =>
            val msg = s"❌ Expected buildpress line format 'id,uri', obtained '$line'"
            Left(BuildpressError.ParseFailure(msg, None))
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
      val completeErrorMsg = failureMsgs.mkString(System.lineSeparator) + System.lineSeparator() +
        "Found ${failures.size} errors when parsing URIs"
      Left(BuildpressError.ParseFailure(completeErrorMsg, None))
    } else {
      Right(parseResults.collect { case Right(uri) => uri }.toList)
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

  /*
  def deriveSbtFileContents(bloopVersion: String): String = {
    // If it contains `+`, then it's not a stable version, source in repository
    if (bloopVersion.contains("+")) {
      s"""val circeDerivation = "io.circe" %% "circe-derivation" % "0.9.0-M3"
         |val circeCore = "io.circe" %% "circe-core" % "0.9.3"
         |val circeGeneric = "io.circe" %% "circe-generic" % "0.9.3"
         |
         | val bloopBaseDir = ???
         | unmanagedSourceDirectories in Compile ++= {
         |   val integrationsMainDir = bloopBaseDir / "integrations"
         |   val pluginMainDir = integrationsMainDir / "sbt-bloop" / "src" / "main"
         |   val scalaDependentDir = {
         |     val scalaVersion = Keys.scalaBinaryVersion.value
         |     if (scalaVersion == "2.10")
         |       bloopBaseDir / "config" / "src" / "main" / s"scala-${Keys.scalaBinaryVersion.value}"
         |     else bloopBaseDir / "config" / "src" / "main" / s"scala-2.11-12"
         |   }
         |
         |   List(
         |     bloopBaseDir / "config" / "src" / "main" / "scala",
         |     scalaDependentDir,
         |     pluginMainDir / "scala",
         |     pluginMainDir / s"scala-sbt-${Keys.sbtBinaryVersion.value}"
         |   )
         | }
         |
         | libraryDependencies := {
         |   val sbtVersion = (sbtBinaryVersion in pluginCrossBuild).value
         |   val scalaVersion = (scalaBinaryVersion in update).value
         |   if (sbtVersion.startsWith("1.")) {
         |     List(circeDerivation)
         |   } else {
         |     val macros = compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
         |     List(circeCore, circeGeneric, macros)
         |   }
         | }""".stripMargin

    } else {
      s"""
         |addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "$bloopVersion")
       """.stripMargin
    }
  }
   */

  def exportSbtBuild(
      buildTool: BuildTool.Sbt,
      bloopVersion: String
  ): Either[BuildpressError, Unit] = {
    // TODO: Don't add sbt plugin if build already has, revisit this in the future
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
          val errorMsg = s"❌ Unexpected exception when writing to to ${buildpressSbtFile.syntax}"
          Left(BuildpressError.ImportFailure(errorMsg, Some(t)))
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
          val msg = s"❌ Unexpected failure when running `${cmd.mkString(" ")}` in $baseDir"
          Left(BuildpressError.ImportFailure(msg, None))
      }
    }

    val metaProjectDir = buildTool.baseDir.resolve("project")
    val buildpressSbtFile = metaProjectDir.resolve("buildpress.sbt")
    for {
      _ <- addSbtPlugin(buildpressSbtFile)
      _ <- runBloopInstall(buildTool.baseDir)
    } yield {
      out.println(s"✅ Exported ${buildTool.baseDir}")
    }
  }
}
