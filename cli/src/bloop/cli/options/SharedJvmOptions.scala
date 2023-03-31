package bloop.cli.options

import caseapp._

// format: off
final case class SharedJvmOptions(

  @HelpMessage("Set the Java home directory")
  @ValueDescription("path")
    javaHome: Option[String] = None,

  @HelpMessage("Use a specific JVM, such as `14`, `adopt:11`, or `graalvm:21`, or `system`")
  @ValueDescription("jvm-name")
  @Name("j")
    jvm: Option[String] = None,
  @HelpMessage("JVM index URL")
  @ValueDescription("url")
  @Hidden
    jvmIndex: Option[String] = None,
  @HelpMessage("Operating system to use when looking up in the JVM index")
  @ValueDescription("linux|linux-musl|darwin|windows|…")
  @Hidden
    jvmIndexOs: Option[String] = None,
  @HelpMessage("CPU architecture to use when looking up in the JVM index")
  @ValueDescription("amd64|arm64|arm|…")
  @Hidden
    jvmIndexArch: Option[String] = None
)
// format: on

object SharedJvmOptions {
  lazy val parser: Parser[SharedJvmOptions]                           = Parser.derive
  implicit lazy val parserAux: Parser.Aux[SharedJvmOptions, parser.D] = parser
  implicit lazy val help: Help[SharedJvmOptions]                      = Help.derive
}
