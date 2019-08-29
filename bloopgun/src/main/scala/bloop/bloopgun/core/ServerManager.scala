package bloop.bloopgun.core

import bloop.bloopgun.util.Environment
import bloop.bloopgun.core.Shell.StatusCommand
import java.io.PrintStream
import java.nio.file.Files

object ServerManager {
  def start(
      bloopVersion: String,
      port: String,
      shell: Shell,
      out: PrintStream
  ): Option[ServerStatus] = {
    // 1. Look for blp-server in classpath. Found? Run that and exit
    //    blp-server will be in the classpath in arch, scoop, nix, macos
    //    default installation will NOT have blp-server
    // 2. Look for `blp-server` in $HOME. Found? Run that and exit
    // 3. Resolve blp-server via coursier
    //    1. Use coursier in classpath
    //    2. Use coursier in dependency
    shell.findCmdInPath("blp-server") match {
      case StatusCommand(0, _) => Some(AvailableAt(List("blp-server")))
      case StatusCommand(errorCode, errorOutput) =>
        out.println(s"Missing `blp-server` in `$$PATH`: ${errorOutput}")

        val installedBlpServer = Environment.executablePath.map { clientPath =>
          val defaultBlpServer = clientPath.getParent.resolve("blp-server")
          if (Files.exists(defaultBlpServer)) {
            Some(AvailableAt(List(defaultBlpServer.toAbsolutePath().toString)))
          } else {
            out.println(s"Missing `blp-server` executable at $defaultBlpServer")
            None
          }
        }

        installedBlpServer.orElse {
          val blpServerUnderHome = Environment.homeDirectory.resolve(".bloop").resolve("blp-server")
          if (Files.exists(blpServerUnderHome))
            Some(AvailableAt(List(blpServerUnderHome.toAbsolutePath().toString)))
          else {
            out.println(s"Missing `blp-server` executable at $blpServerUnderHome")
            import scala.concurrent.ExecutionContext.Implicits.global
            DependencyResolution.resolveWithErrors(
              "ch.epfl.scala",
              "bloop-frontend_2.12",
              bloopVersion,
              System.out
            ) match {
              case Right(jars) => Some(ResolvedAt(jars))
              case Left(value) => out.println(value.getMessage()); None
            }
          }
        }

    }

    shell.runCommand(List("cmd"), Environment.cwd, None)
    ???
  }
}
