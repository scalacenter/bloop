package bloop.cli

import java.io.{InputStream, PrintStream}

import bloop.io.AbsolutePath
import caseapp.Hidden

/**
 * Describes the common options for any command or CLI operation.
 *
 * They exist for two purposes: testing and nailgun. In both cases we
 * need a precise handling of these parameters because they change
 * depending on the environment we're running on.
 *
 * They are hidden because they are optional.
 */
case class CommonOptions(
    @Hidden workingDirectory: String = System.getProperty("user.dir"),
    @Hidden out: PrintStream = System.out,
    @Hidden in: InputStream = System.in,
    @Hidden err: PrintStream = System.err,
) {
  def workingPath: AbsolutePath = AbsolutePath(workingDirectory)
}

object CommonOptions {
  final val default = CommonOptions()
}
