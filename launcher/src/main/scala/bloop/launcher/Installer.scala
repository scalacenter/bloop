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
