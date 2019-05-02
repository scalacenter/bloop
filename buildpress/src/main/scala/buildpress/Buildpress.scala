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

abstract class Buildpress(
    bloopVersion: String,
    in: InputStream,
    out: PrintStream,
    err: PrintStream,
    shell: Shell,
    explicitBuildpressHome: Option[AbsolutePath],
    implicit val cwd: AbsolutePath
) {
  def exit(exitCode: Int): Unit

  def findBuildpressHome: Either[BuildpressError.InvalidBuildpressHome, AbsolutePath] = {
    explicitBuildpressHome match {
      case Some(buildpressHome) => Right(buildpressHome)
      case None =>
        val processUserBuildpress = Option(System.getProperty("buildpress.home")).map {
          userBuildpressHomePath =>
            try {
              val userBuildpressHome = AbsolutePath(userBuildpressHomePath)
              if (userBuildpressHome.getParent.exists) {
                Files.createDirectory(userBuildpressHome.underlying)
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

  def findBuildpressInputFile: Either[BuildpressError.InvalidInputFile, AbsolutePath] = {
    Option(System.getProperty("buildpress.file")) match {
      case None =>
        val msg = "❌ Missing buildpress file, pass it via `-Dbuildpress.file`"
        Left(BuildpressError.InvalidInputFile(msg))
      case Some(userBuildpressFile) =>
        try {
          val p = AbsolutePath(userBuildpressFile)
          if (p.exists) Right(p)
          else Left(BuildpressError.InvalidInputFile("❌ Input file '${p.syntax}' doesn't exist"))
        } catch {
          case t: InvalidPathException =>
            val msg = s"❌ Invalid input file $userBuildpressFile, received ${t.getMessage}"
            Left(BuildpressError.InvalidInputFile(msg))
        }
    }
  }

  def run(): Unit = {
    findBuildpressHome match {
      case Left(invalidHome) => err.println(invalidHome.msg)
      case Right(buildpressHome) =>
        val buildpressCacheDir = buildpressHome.resolve("cache")
        Files.createDirectories(buildpressCacheDir.underlying)
        findBuildpressInputFile match {
          case Left(invalidFile) =>
            err.println(invalidFile.msg)
            exit(1)
          case Right(buildpressFile) =>
            val bytes = Files.readAllBytes(buildpressFile.underlying)
            val contents = new String(bytes, StandardCharsets.UTF_8)
            parseUris(contents) match {
              case Left(parseError) =>
                err.println(parseError.msg)
                exit(1)
              case Right(uris) =>
            }
        }

    }

    detectBuildTool(cwd).foreach { buildTool =>
      out.println(s"Detected build tool $buildTool")
      buildTool match {
        case build: BuildTool.Sbt => exportSbtBuild(build)
        case _ => ???
      }
    }
  }

  def cloneUri(uri: URI, cacheDir: AbsolutePath): Either[BuildpressError.CloningFailure, Unit] = {
    def hasFragment = uri.getFragment ne null
    def withoutMarkerScheme = {
      if (hasMarkerScheme)
        if (hasFragment)
          new URI(uri.getRawSchemeSpecificPart + "#" + uri.getRawFragment)
        else
          new URI(uri.getRawSchemeSpecificPart)
      else
        uri
    }

    uri.getScheme() match {
        case 
    }

    val hasMarkerScheme = new URI(uri.getRawSchemeSpecificPart).getScheme ne null
    if (uri.getRawSchemeSpecificPart())
      uri
  }

  def parseUris(contents: String): Either[BuildpressError.ParseFailure, List[URI]] = {
    val linesIterator = contents.split(System.lineSeparator()).iterator
    val parseResults = linesIterator.zipWithIndex.map {
      case (line, idx) =>
        try Right(new URI(line))
        catch {
          case t: URISyntaxException =>
            val lineNumber = idx + 1
            val msg = s"❌ Expected URI syntax at line $lineNumber, obtained '$line'"
            Left(BuildpressError.ParseFailure(msg, Some(t)))
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

  def exportSbtBuild(buildTool: BuildTool.Sbt): Either[BuildpressError, Unit] = {
    // Write file with bloop version enabled
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
          val errorMsg = s"Unexpected exception when writing to to ${buildpressSbtFile.syntax}"
          Left(BuildpressError.ImportFailure(errorMsg, Some(t)))
      }
    }

    def runBloopInstall(baseDir: AbsolutePath) = {
      // Run bloop install for 15 minutes at maximum per project
      val cmd = List("sbt", "bloopInstall")
      shell.runCommand(cmd, cwd.underlying, Some(15L * 60L), None) match {
        case status if status.isOk => Right(())
        case failed =>
          val msg = s"Unexpected failure when running `${cmd}` in ${cwd}"
          Left(BuildpressError.ImportFailure(msg, None))
      }
    }

    val metaProjectDir = buildTool.baseDir.resolve("project")
    val buildpressSbtFile = metaProjectDir.resolve("buildpress.sbt")
    for {
      _ <- addSbtPlugin(buildpressSbtFile)
      _ <- runBloopInstall(buildTool.baseDir)
    } yield {
      out.println(s"✅ exported ${cwd}")
    }
  }
}
