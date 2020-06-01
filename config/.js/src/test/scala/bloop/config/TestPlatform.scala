package bloop.config

object TestPlatform {
  def getResourceAsString(resource: String): String =
    NodeFS.readFileSync(NodePath.join("config", "src", "test", "resources", resource), "utf8")
}
