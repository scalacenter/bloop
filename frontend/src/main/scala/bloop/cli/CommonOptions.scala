package bloop.cli

import java.io.InputStream
import java.io.PrintStream
import java.util.Properties

import bloop.cli.CliParsers._
import bloop.io.AbsolutePath

import caseapp.Hidden
import caseapp.core.help.Help
import caseapp.core.parser.Parser

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
    @Hidden ngout: PrintStream = System.out,
    @Hidden ngerr: PrintStream = System.err,
    @Hidden env: CommonOptions.PrettyProperties = CommonOptions.currentEnv
) {
  def workingPath: AbsolutePath = AbsolutePath(workingDirectory)

  def withEnv(env: CommonOptions.PrettyProperties): CommonOptions =
    copy(env = env)
}

object CommonOptions {
  final val default: CommonOptions = CommonOptions()

  // Our own version of properties in which we override `toString`
  final class PrettyProperties(val toMap: Map[String, String]) {
    override def toString: String = {
      toMap.keySet.mkString(", ")
    }

    def containsKey(key: String): Boolean = toMap.contains(key)
    def withProperty(key: String, value: String): PrettyProperties = {
      new PrettyProperties(toMap + (key -> value))
    }
  }

  object PrettyProperties {
    def from(p: Properties): PrettyProperties = {
      import scala.collection.JavaConverters.propertiesAsScalaMapConverter
      val propertiesMap = p.asScala.toMap
      new PrettyProperties(propertiesMap)
    }
  }

  final lazy val currentEnv: PrettyProperties = {
    import scala.collection.JavaConverters._
    new PrettyProperties(System.getenv().asScala.toMap)
  }

  implicit lazy val parser: Parser[CommonOptions] = Parser.derive
  implicit lazy val help: Help[CommonOptions] = Help.derive
}
