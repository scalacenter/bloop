package bloop.util
import bloop.config.Config

object ExtraPlatform {
  implicit class PlatformXtension(platform: Config.Platform.type) {
      def unapply(config: Config.PlatformConfig): Option[Config.PlatformConfig] = ???
  }
}
