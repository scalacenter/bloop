package bloop.config

object TestPlatform {
  def getResourceAsString(resource: String): String =
    getClass.getClassLoader.getResourceAsStream(resource).readAllBytes().map(_.toChar).mkString
}
