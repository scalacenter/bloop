package bloop.launcher.core

import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Paths

import bloop.launcher.{printError, printQuoted, println}

import scala.util.control.NonFatal

object Installer {
  import java.net.URL
  def defaultWebsiteURL(bloopVersion: String): URL = {
    new URL(
      s"https://github.com/scalacenter/bloop/releases/download/v${bloopVersion}/install.py"
    )
  }

  def installBloopBinaryInHomeDir(
      downloadDir: Path,
      bloopDirectory: Path,
      bloopVersion: String,
      out: PrintStream,
      detectServerState: String => Option[ServerStatus],
      shell: Shell,
      downloadURL: URL
  ): Option[ServerStatus] = {
    if (!shell.isPythonInClasspath) {
      println(Feedback.SkippingFullInstallation, out)
      None
    } else {
      import java.io.FileOutputStream
      import java.nio.channels.Channels
      import scala.util.{Failure, Success, Try}
      val isLocalScript = downloadURL.toURI().getScheme() == "file"
      val installpyPath = Try {
        println(Feedback.downloadingInstallerAt(downloadURL), out)
        val target = downloadDir.resolve("install.py")
        val targetPath = target.toAbsolutePath.toString

        val channel = Channels.newChannel(downloadURL.openStream())
        val fos = new FileOutputStream(targetPath)
        try {
          val bytesTransferred = fos.getChannel.transferFrom(channel, 0, Long.MaxValue)
        } finally {
          fos.close()
        }

        // The file should already be created, make it executable so that we can run it
        target.toFile.setExecutable(true)
        targetPath
      }

      installpyPath match {
        case Success(targetPath) =>
          // Run the installer without a timeout (no idea how much it can last)
          val bloopPath = bloopDirectory.toAbsolutePath.toString
          val installArgs = {
            val args0 = List("--dest", bloopPath)
            val ivyHome = System.getProperty("ivy.home")
            val bloopLocalHome = System.getProperty("bloop.home")
            if (!isLocalScript) args0
            else if (ivyHome == null || bloopLocalHome == null) args0
            else "--ivy-home" :: ivyHome :: "--bloop-home" :: bloopLocalHome :: args0
          }

          val installCmd = "python" :: targetPath :: installArgs
          val installStatus = shell.runCommand(installCmd, None)
          if (installStatus.isOk) {
            // We've just installed bloop in `$HOME/.bloop`, let's now detect the installation
            if (!installStatus.output.isEmpty)
              printQuoted(installStatus.output, out)
            println(Feedback.installationLogs(bloopDirectory), out)
            detectServerState(bloopVersion)
          } else {
            printError(s"Failed to run '${installCmd.mkString(" ")}'", out)
            printQuoted(installStatus.output, out)
            None
          }

        case Failure(NonFatal(t)) =>
          t.printStackTrace(out)
          printError(Feedback.failedToDownloadInstallerAt(downloadURL), out)
          None
        case Failure(t) => throw t // Throw non-fatal exceptions
      }
    }
  }

  import java.io.File

  import coursier._
  import coursier.util.{Gather, Task}

  import scala.concurrent.ExecutionContext.Implicits.global

  def fromDependencyToString(dep: coursier.Dependency): String =
    s"${dep.mavenPrefix}:${dep.version}"

  def resolveServer(
      bloopVersion: String,
      withScalaSuffix: Boolean,
      out: PrintStream
  ): (Dependency, Resolution) = {
    val moduleName = if (withScalaSuffix) name"bloop-frontend_2.12" else name"bloop-frontend"
    val bloopDependency = Dependency(Module(org"ch.epfl.scala", moduleName), bloopVersion)
    val stringBloopDep = fromDependencyToString(bloopDependency)
    println(Feedback.resolvingDependency(stringBloopDep), out)
    val start = Resolution(Set(bloopDependency))

    val repositories = Seq(
      Cache.ivy2Local,
      MavenRepository("https://repo1.maven.org/maven2"),
      MavenRepository("https://oss.sonatype.org/content/repositories/staging/"),
      MavenRepository("https://dl.bintray.com/scalacenter/releases/"),
      MavenRepository("https://dl.bintray.com/scalameta/maven/")
    )

    val fetch = Fetch.from(repositories, Cache.fetch[Task]())
    (bloopDependency, start.process.run(fetch).unsafeRun())
  }

  def fetchJars(r: Resolution, out: PrintStream): Seq[Path] = {
    val localArtifacts: Seq[(Boolean, Either[FileError, File])] = {
      Gather[Task]
        .gather(r.artifacts().map { artifact =>
          Cache.file[Task](artifact).run.map(artifact.optional -> _)
        })
        .unsafeRun()
    }

    val fileErrors = localArtifacts.collect {
      case (isOptional, Left(error)) if !isOptional || !error.notFound => error
    }
    if (fileErrors.isEmpty) {
      localArtifacts.collect { case (_, Right(f)) => f }.map(_.toPath)
    } else {
      val prettyFileErrors = fileErrors.map(_.describe).mkString("\n")
      val errorMsg = s"Fetch error(s):\n${prettyFileErrors}"
      printError(errorMsg, out)
      Nil
    }
  }
}
