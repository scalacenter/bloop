package hello

object AppWithConflictingResources {
  def main(args: Array[String]): Unit = {
    val obtained = resourceContent("application.conf")
    assert(obtained == Some("OK"))
    println("Resource application.conf was successfully loaded")
  }

  import java.io.{BufferedReader, InputStreamReader}
  def resourceContent(name: String): Option[String] = {
    Option(getClass.getClassLoader.getResourceAsStream(name)).map { stream =>
      val reader = new BufferedReader(new InputStreamReader(stream))
      reader.readLine()
    }
  }
}
