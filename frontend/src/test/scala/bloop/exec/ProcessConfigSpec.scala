package bloop.exec

import scala.collection.mutable.Buffer

import org.junit.Test
import org.junit.Assert.{assertEquals, assertNotEquals}

import bloop.logging.AbstractLogger
import bloop.tasks.ProjectHelpers

class ProcessConfigSpec {

  class RecordingLogger extends AbstractLogger {
    private val messages: Buffer[(String, String)] = Buffer.empty
    def clear(): Unit = messages.clear()
    def getMessages(): List[(String, String)] = messages.toList

    override val name: String = "RecordingLogger"
    override val ansiCodesSupported: Boolean = true

    override def verbose[T](op: => T): T = op
    override def debug(msg: String): Unit = messages += (("debug", msg))
    override def info(msg: String): Unit = messages += (("info", msg))
    override def error(msg: String): Unit = messages += (("error", msg))
    override def warn(msg: String): Unit = messages += (("warn", msg))
    private def trace(msg: String): Unit = messages += (("trace", msg))
    override def trace(ex: Throwable): Unit = {
      ex.getStackTrace.foreach(ste => trace(ste.toString))
      Option(ex.getCause).foreach { cause =>
        trace("Caused by:")
        trace(cause)
      }
    }
  }

  val packageName = "foo.bar"
  val mainClassName = "Main"

  object ArtificialSources {
    val `A.scala` = s"""package $packageName
                       |object $mainClassName {
                       |  def main(args: Array[String]): Unit = {
                       |    if (args.contains("crash")) throw new Exception
                       |    println(s"Arguments: $${args.mkString(", ")}")
                       |    System.err.println("testing stderr")
                       |  }
                       |}""".stripMargin
  }

  val dependencies = Map.empty[String, Set[String]]
  val NonForkingEnv = JavaEnv.default(fork = false)
  val ForkingEnv = JavaEnv.default(fork = true)
  val runnableProject = Map(
    ProjectHelpers.RootProject -> Map("A.scala" -> ArtificialSources.`A.scala`))

  private def run(env: JavaEnv, args: Array[String])(
      op: (Int, List[(String, String)]) => Unit): Unit =
    ProjectHelpers.checkAfterCleanCompilation(runnableProject, dependencies) { state =>
      val project = ProjectHelpers.getProject(ProjectHelpers.RootProject, state)
      val classpath = project.classesDir +: project.classpath
      val config = ProcessConfig(env, classpath)
      val logger = new RecordingLogger
      val exitCode = config.runMain(s"$packageName.$mainClassName", args, logger)
      val messages = logger.getMessages
      op(exitCode, messages)
    }

  @Test
  def canRunWithoutForking(): Unit = run(NonForkingEnv, Array("foo", "bar", "baz")) {
    case (exitCode, messages) =>
      assertEquals(0, exitCode.toLong)
      assert(messages.contains(("info", "Arguments: foo, bar, baz")))
      assert(messages.contains(("error", "testing stderr")))
  }

  @Test
  def canRunWithForking(): Unit = run(ForkingEnv, Array("foo", "bar", "baz")) {
    case (exitCode, messages) =>
      assertEquals(0, exitCode.toLong)
      assert(messages.contains(("info", "Arguments: foo, bar, baz")))
      assert(messages.contains(("error", "testing stderr")))
  }

  @Test
  def reportsExceptionsWithoutForking(): Unit = run(NonForkingEnv, Array("crash")) {
    case (exitCode, messages) =>
      assertNotEquals(0, exitCode.toLong)
      assert(messages.count(_._1 == "trace") > 10)
  }

  @Test
  def reportsExceptionsWithForking(): Unit = run(ForkingEnv, Array("crash")) {
    case (exitCode, messages) =>
      assertNotEquals(0, exitCode.toLong)
      assert(messages.count(_._1 == "error") == 3)
  }

}
