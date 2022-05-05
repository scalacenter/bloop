package bloop

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration

import bloop.cli.ExitStatus
import bloop.data.JdkConfig
import bloop.exec.Forker
import bloop.exec.JvmProcessForker
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.TestUtil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Array(classOf[bloop.FastTests]))
class ForkerSpec {

  val packageName = "foo.bar"
  val mainClassName = "Main"

  object ArtificialSources {
    val `A.scala` =
      s"""package $packageName
         |object $mainClassName {
         |  def main(args: Array[String]): Unit = {
         |    if (args.contains("crash")) throw new Exception
         |    else if (args.contains("print-env")) {
         |      sys.env.foreach{case (k, v) => println(s"$$k = $$v")}
         |    } else if (args.contains("mixed-newlines")) {
         |      print("first\\n")
         |      print("second\\r\\n")
         |      print("third\\r\\n")
         |    } else { 
         |      println(s"Arguments: $${args.mkString(", ")}")
         |      val cwd = new java.io.File(sys.props("user.dir")).getCanonicalPath
         |      println(s"CWD: $$cwd")
         |      System.err.println("testing stderr")
         |    }
         |  }
         |}""".stripMargin
  }

  val dependencies: Map[String, Set[String]] = Map.empty[String, Set[String]]
  val runnableProject: Map[String, Map[String, String]] = Map(
    TestUtil.RootProject -> Map("A.scala" -> ArtificialSources.`A.scala`)
  )

  private def run(
      cwd: AbsolutePath,
      args: Array[String],
      extraClasspath: Array[AbsolutePath] = Array.empty,
      envs: List[String] = Nil
  )(
      op: (Int, List[(String, String)]) => Unit
  ): Unit =
    TestUtil.checkAfterCleanCompilation(runnableProject, dependencies) { state =>
      val project = TestUtil.getProject(TestUtil.RootProject, state)
      val env = JdkConfig.default
      val classpath = project.fullRuntimeClasspath(state.build.getDagFor(project), state.client)
      val config = JvmProcessForker(env, classpath)
      val logger = new RecordingLogger
      val opts = state.commonOptions.copy(env = TestUtil.runAndTestProperties)
      val mainClass = s"$packageName.$mainClassName"
      val wait = Duration.apply(25, TimeUnit.SECONDS)
      val exitCode = TestUtil.await(wait)(
        config.runMain(
          cwd,
          mainClass,
          args,
          Array.empty,
          envVars = envs,
          logger.asVerbose,
          opts,
          extraClasspath
        )
      )
      val messages = logger.getMessages()
      op(exitCode, messages)
    }

  @Test
  def detectAnyNewline(): Unit = TestUtil.withinWorkspace { tmp =>
    run(tmp, Array("mixed-newlines")) {
      case (exitCode, messages) =>
        assertEquals(0, exitCode.toLong)
        val expected = List(
          ("info", "first"),
          ("info", "second"),
          ("info", "third")
        )
        val nonLogged = expected.filter(v => !messages.contains(v))
        assert(nonLogged.isEmpty)
    }
  }

  @Test
  def canRun(): Unit = TestUtil.withinWorkspace { tmp =>
    run(tmp, Array("foo", "bar", "baz")) {
      case (exitCode, messages) =>
        assertEquals(0, exitCode.toLong)
        assert(messages.contains(("info", "Arguments: foo, bar, baz")))
        assert(messages.contains(("error", "testing stderr")))
    }
  }

  @Test
  def processEnv(): Unit = TestUtil.withinWorkspace { tmp =>
    val envs =
      List(
        "FOO=bar",
        "TEST=http://localhost:8086?test=1&foo=bar"
      )
    run(tmp, Array("print-env"), envs = envs) {
      case (exitCode, messages) =>
        assertEquals(0, exitCode.toLong)
        assert(messages.contains(("info", "FOO = bar")))
        assert(messages.contains(("info", "TEST = http://localhost:8086?test=1&foo=bar")))
    }
  }

  @Test
  def canHandleLongClasspaths(): Unit = TestUtil.withinWorkspace { tmp =>
    TestUtil.withinWorkspace { tmpJarDir =>
      val longCp = (1 to 3000).map { i =>
        val tmpFile = tmpJarDir.resolve(s"forkerspec-temp-$i.jar").underlying
        AbsolutePath(Files.createFile(tmpFile))
      }.toArray

      val charLimitMsg =
        s"""|Supplied command to fork exceeds character limit of 30000
            |Creating a temporary MANIFEST jar for classpath entries
            |""".stripMargin

      val cleanupPrefix = "Cleaning up temporary MANIFEST jar: "

      def isCleanupMessage(message: (String, String)): Boolean = message match {
        case ("debug", msg) if msg.startsWith(cleanupPrefix) =>
          true
        case _ =>
          false
      }

      run(tmp, Array("foo", "bar", "baz"), longCp) {
        case (exitCode, messages) =>
          assertEquals(0, exitCode.toLong)
          assert(messages.contains(("debug", charLimitMsg)))

          val cleanupMessage = messages.filter(isCleanupMessage)
          assertEquals(cleanupMessage.size.toLong, 1L)

          val tempManifestJar = Paths.get(cleanupMessage.head._2.stripPrefix(cleanupPrefix))
          assert(tempManifestJar.isAbsolute)
          assert(Files.notExists(tempManifestJar))

          assert(messages.contains(("info", "Arguments: foo, bar, baz")))
          assert(messages.contains(("error", "testing stderr")))
      }
    }
  }

  @Test
  def reportsExceptions(): Unit = TestUtil.withinWorkspace { tmp =>
    run(tmp, Array("crash")) {
      case (exitCode, messages) =>
        assertNotEquals(0, exitCode.toLong)
        assert(messages.count(_._1 == "error") == 3)
    }
  }

  @Test
  def runReportsMissingCWD(): Unit = TestUtil.withinWorkspace { tmp =>
    val nonExisting = "does-not-exist"
    val cwd = tmp.resolve(nonExisting)
    run(cwd, Array.empty) {
      case (exitCode, messages) =>
        val expected: ((String, String)) => Boolean = {
          case ("error", msg) => msg.contains(nonExisting)
          case _ => false
        }
        val exitStatus = Forker.exitStatus(exitCode)
        assertEquals(ExitStatus.RunError, exitStatus)
        assert(messages.exists(expected), s"Could not find expected error messages in $messages")
    }
  }

  @Test
  def runHasCorrectWorkingDirectory(): Unit = TestUtil.withinWorkspace { tmp =>
    val cwd = tmp.resolve("expected-dir")
    Files.createDirectory(cwd.underlying)
    run(cwd, Array.empty) {
      case (exitCode, messages) =>
        val expected = "info" -> s"CWD: ${cwd.underlying.toRealPath()}"
        assertEquals(0, exitCode.toLong)
        assert(messages.contains(expected), s"$messages did not contain $expected")
    }
  }
}
