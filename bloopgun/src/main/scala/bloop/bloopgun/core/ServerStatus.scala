package bloop.bloopgun.core

import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Files

import bloop.bloopgun.util.Environment
import bloop.bloopgun.core.Shell.StatusCommand

sealed trait ServerStatus
sealed trait LocatedServer extends ServerStatus
case class AvailableAt(binary: List[String]) extends LocatedServer
case class ResolvedAt(files: Seq[Path]) extends LocatedServer
case class ListeningAndAvailableAt(binary: List[String]) extends ServerStatus

object ServerStatus {

  /**
   * Finds server to run in the system if bloop is installed, otherwise
   * resolves bloop server via coursier.
   *
   * This method will keep trying any of these actions until the server has
   * been located:
   *
   *   1. Look for `blp-server` in classpath (common case if bloop was installed
   *      via a package manager, e.g. `arch`, `scoop`, `nix`, `brew`).
   *   2. Look for a `blp-server` located right next to the binary of bloopgun
   *      that is running because `blp-server` and `bloop` are always located
   *      together when bloop is installed.
   *   3. Look for `blp-server` in `$HOME/.bloop` where bloop installation script
   *      installs bloop if the curl installation method is used. Works in case
   *      the running bloopgun client is a binary dependency of an existing tool.
   *   4. Resolve Bloop server via coursier.
   */
  def findServerToRun(
      bloopVersion: String,
      shell: Shell,
      out: PrintStream
  ): Option[LocatedServer] = {
    shell.findCmdInPath("blp-server") match {
      case StatusCommand(0, _) => Some(AvailableAt(List("blp-server")))
      case StatusCommand(errorCode, errorOutput) =>
        out.println(s"Missing `blp-server` in `$$PATH`: ${errorOutput}")

        val installedBlpServer = Environment.executablePath.flatMap { clientPath =>
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
              case Left(value) =>
                out.println("Unexpected error when resolving Bloop server via coursier!")
                out.println(value.getMessage())
                None
            }
          }
        }
    }
  }
}
