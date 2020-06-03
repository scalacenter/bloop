package bloop.config

import java.io.ByteArrayInputStream

object TestPlatform {
  def getResourceAsStream(resource: String): java.io.InputStream =
    new ByteArrayInputStream(
      NodeFS
        .readFileSync(NodePath.join("config", "src", "test", "resources", resource), "utf8")
        .getBytes
    )
}
