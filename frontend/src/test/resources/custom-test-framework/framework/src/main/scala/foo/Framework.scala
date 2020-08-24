package foo

import sbt.testing._

trait Test
trait Test2
case class SCFingerprint(superclassName: String) extends SubclassFingerprint {
  def isModule: Boolean = false
  def requireNoArgConstructor: Boolean = false
}

class Framework extends sbt.testing.Framework {
  def name(): String = "My awesome framework"
  def runner(args: Array[String], remoteArgs: Array[String], testClassLoader: ClassLoader): Runner = new Runner(args, remoteArgs)
  def fingerprints(): Array[Fingerprint] =
    Array(SCFingerprint("foo.Test"), SCFingerprint("foo.Test2"))
}

class Runner(val args: Array[String], val remoteArgs: Array[String]) extends sbt.testing.Runner {
  override def done(): String = ""
  override def tasks(tasks: Array[TaskDef]): Array[Task] = tasks.map { t =>
    new Task {
      def execute(handler: EventHandler, loggers: Array[Logger]): Array[Task] = {
        loggers.foreach(l => l.info("Running task: " + t.fullyQualifiedName()))
        Array.empty
      }
      def tags(): Array[String] = Array.empty
      def taskDef(): TaskDef = t
    }
  }
}