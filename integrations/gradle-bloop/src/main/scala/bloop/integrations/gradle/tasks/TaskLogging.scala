package bloop.integrations.gradle.tasks

import org.gradle.api.Task

/**
  * Logger helper methods for Tasks implemented in Scala
  */
trait TaskLogging {
  this: Task =>

  def debug(msg: String): Unit = {
    getLogger.debug(msg, Seq.empty : _*)
  }

  def info(msg: String): Unit = {
    getLogger.info(msg, Seq.empty : _*)
  }
}
