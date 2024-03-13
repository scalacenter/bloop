package hello

object Hello {
  def greet(name: String): String = {
    // We do this to check that environment variables work
    Environment.requireEnvironmentVariable()
    s"Hello, $name!"
  }
}
