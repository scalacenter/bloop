package bloop.launcher.core

import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Paths

import bloop.bloopgun.util.Environment
import bloop.bloopgun.core.{ServerStatus, Shell}
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
          val installStatus = shell.runCommand(installCmd, Environment.cwd, None)
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
}
