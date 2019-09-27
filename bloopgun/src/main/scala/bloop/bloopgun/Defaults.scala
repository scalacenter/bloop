package bloop.bloopgun

import java.nio.file.Paths

object Defaults {
  val Version = "0.9.3"
  val Host = "127.0.0.1"
  val Port = 8212

  val env: Map[String, String] = {
    import scala.collection.JavaConverters._
    System.getenv().asScala.toMap
  }

  object Time {
    val DefaultHeartbeatIntervalMillis = 500.toLong
    val SendThreadWaitTerminationMillis = 5000.toLong
  }
}
