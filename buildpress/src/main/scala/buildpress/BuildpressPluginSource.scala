package buildpress
import buildpress.io.AbsolutePath

sealed trait BuildpressPluginSource
object BuildpressPluginSource {
  final case class BinaryDependency(bloopVersion: String) extends BuildpressPluginSource
  final case class SourceDependency(bloopBaseDir: AbsolutePath) extends BuildpressPluginSource

}
