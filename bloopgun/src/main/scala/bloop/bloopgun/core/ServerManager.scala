package bloop.bloopgun.core

object ServerManager {
  def start(host: String, port: String, shell: Shell): Option[ServerStatus] = {
    // 1. Look for blp-server in classpath. Found? Run that and exit
    //    blp-server will be in the classpath in arch, scoop, nix, macos
    //    default installation will NOT have blp-server
    // 2. Look for `blp-server` in $HOME. Found? Run that and exit
    // 3. Resolve blp-server via coursier
    //    1. Use coursier in classpath
    //    2. Use coursier in dependency
    ???
  }
}
