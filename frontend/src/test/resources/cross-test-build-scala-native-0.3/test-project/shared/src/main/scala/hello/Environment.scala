package hello

object Environment {
  def requireEnvironmentVariable(): Unit = {
    sys.env.get("BLOOP_OWNER").getOrElse(sys.error("Missing BLOOP_OWNER env variable!"))
  }
}
