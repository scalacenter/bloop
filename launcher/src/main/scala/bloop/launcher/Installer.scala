package bloop.launcher

import java.io.PrintStream
import java.nio.file.Path

import scala.util.control.NonFatal

object Installer {
  def installBloopBinaryInHomeDir(
      downloadDir: Path,
      bloopDirectory: Path,
      bloopVersion: String,
      out: PrintStream,
      detectServerState: String => Option[ServerState]
  ): Option[ServerState] = {
    if (!isPythonInClasspath) {
      printError("Python not detected in the classpath, don't attempt full bloop installation", out)
      None
    } else {
      import java.net.URL
      import java.nio.channels.Channels
      import java.io.FileOutputStream

      val website = new URL(
        s"https://github.com/scalacenter/bloop/releases/download/v${bloopVersion}/install.py"
      )

      import scala.util.{Try, Success, Failure}
      val installpyPath = Try {
        val target = downloadDir.resolve("install.py")
        val targetPath = target.toAbsolutePath.toString
        val channel = Channels.newChannel(website.openStream())
        val fos = new FileOutputStream(targetPath)
        val bytesTransferred = fos.getChannel().transferFrom(channel, 0, Long.MaxValue)

        // The file should already be created, make it executable so that we can run it
        target.toFile.setExecutable(true)
        targetPath
      }

      installpyPath match {
        case Success(targetPath) =>
          // Run the installer without a timeout (no idea how much it can last)
          val bloopPath = bloopDirectory.toAbsolutePath.toString
          val installCmd = List("python", targetPath, "--dest", bloopPath)
          val installStatus = Utils.runCommand(installCmd, None)
          if (installStatus.isOk) {
            // We've just installed bloop in `$HOME/.bloop`, let's now detect the installation
            if (!installStatus.output.isEmpty)
              printQuoted(installStatus.output, out)
            detectServerState(bloopVersion)
          } else {
            printError(s"Failed to run '${installCmd.mkString(" ")}'", out)
            printQuoted(installStatus.output, out)
            None
          }

        case Failure(NonFatal(t)) =>
          t.printStackTrace(out)
          printError(s"^ An error happened when downloading installer ${website}...", out)
          println("The launcher will now try to resolve and run the build server", out)
          None
        case Failure(t) => throw t // Throw non-fatal exceptions
      }
    }
  }

  import java.io.File
  import scala.concurrent.ExecutionContext.Implicits.global
  import coursier._
  import coursier.util.{Task, Gather}

  def resolveServer(bloopVersion: String, withScalaSuffix: Boolean): (Dependency, Resolution) = {
    val moduleName = if (withScalaSuffix) name"bloop-frontend_2.12" else name"bloop-frontend"
    val bloopDependency = Dependency(Module(org"ch.epfl.scala", moduleName), bloopVersion)
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
    val localArtifacts: Seq[Either[FileError, File]] =
      Gather[Task].gather(r.artifacts().map(Cache.file[Task](_).run)).unsafeRun()
    val fileErrors = localArtifacts.collect { case Left(error) => error }
    if (fileErrors.isEmpty) {
      localArtifacts.collect { case Right(f) => f }.map(_.toPath)
    } else {
      val prettyFileErrors = fileErrors.map(_.describe).mkString("\n")
      val errorMsg = s"Fetch error(s):\n${prettyFileErrors.mkString("\n")}"
      printError(errorMsg, out)
      Nil
    }
  }

  def isPythonInClasspath: Boolean = {
    Utils.runCommand(List("python", "--help"), Some(2)).isOk
  }
}
