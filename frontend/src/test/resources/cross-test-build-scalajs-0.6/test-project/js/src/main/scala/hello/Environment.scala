package hello

object Environment {
  def requireEnvironmentVariable(): Unit = {
    import scala.scalajs.js
    val or = js.Dynamic.global.process.env.BLOOP_OWNER.asInstanceOf[js.UndefOr[String]]
    or.toOption.getOrElse(sys.error(s"Missing environment variable BLOOP_OWNER in Scala.js"))
  }
}
