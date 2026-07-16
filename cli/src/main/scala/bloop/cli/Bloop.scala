package bloop.cli

import caseapp.core.app.CommandsEntryPoint
import caseapp.core.app.Command

object Bloop extends CommandsEntryPoint {
  def progName: String = "bloop"
  def commands: Seq[Command[_]] = Seq(
    Exit,
    Output,
    Start,
    Status
  )
  override def defaultCommand: Option[Command[_]] = Some(Default)

  override def main(args: Array[String]): Unit = {
    val (properties, rest) = partitionLauncherProperties(args)
    properties.foreach { case (key, value) => System.setProperty(key, value) }
    super.main(rest)
  }

  /**
   * The native binary is built with `-H:-ParseRuntimeOptions`, so the launcher no longer
   * turns `-D` arguments into system properties on its own. The `-D` tokens before the
   * first `--` are the launcher's own properties (`bloop.java-opts`, `bloop.server`, ...):
   * they are separated out here, before the command is selected, and applied as system
   * properties. Everything else — the command, its options, and every token from `--`
   * onwards — is left untouched so that e.g. `bloop test foo -- -Dkey=value` reaches the
   * test framework.
   */
  private[cli] def partitionLauncherProperties(
      args: Array[String]
  ): (Seq[(String, String)], Array[String]) = {
    val dashDash = args.indexOf("--")
    val (launcherScope, rest) =
      if (dashDash < 0) (args.toSeq, Seq.empty[String])
      else (args.take(dashDash).toSeq, args.drop(dashDash).toSeq)
    val (rawProperties, kept) = launcherScope.partition(_.startsWith("-D"))
    val properties = rawProperties.map { arg =>
      arg.stripPrefix("-D").split("=", 2) match {
        case Array(key, value) => key -> value
        case Array(key) => key -> ""
      }
    }
    (properties, (kept ++ rest).toArray)
  }
}
