package bloop.config

object TestPlatform {
  def getResourceAsStream(resource: String): java.io.InputStream =
    getClass.getClassLoader.getResourceAsStream(resource)
}
