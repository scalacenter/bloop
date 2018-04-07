package bloop.testing

import bloop.cli.Commands
import bloop.tasks.TestUtil
import org.junit.Assert.assertEquals
import org.junit.Test
import bloop.config.Config.TestOptions
import bloop.engine.{Run, State}
import bloop.exec.JavaEnv
import bloop.logging.RecordingLogger

class TestLoggingSpec {

  @Test
  def concurrentTestRunsHaveDifferentStreams = {
    val projectName = "with-tests"
    val moduleName = "with-tests-test"
    val state = {
      val state0 = TestUtil.loadTestProject(projectName)
      val inProcessEnv = JavaEnv.default
      val build = state0.build
      val beforeCompilation = state0.copy(
        build = build.copy(projects =
          build.projects.map(_.copy(javaEnv = inProcessEnv, testOptions = TestOptions.empty))))
      val action = Run(Commands.Compile(moduleName))
      val state1 = TestUtil.blockingExecute(action, beforeCompilation)
      val commonOptions = state1.commonOptions.copy(env = TestUtil.runAndTestProperties)
      state1.copy(commonOptions = commonOptions)
    }

    val testAction = Run(Commands.Test(moduleName))
    def mkThread(state: State) = {
      new Thread {
        override def run(): Unit = {
          TestUtil.blockingExecute(testAction, state)
          ()
        }
      }
    }

    val l0 = new RecordingLogger
    val state0 = state.copy(logger = l0)
    val thread0 = mkThread(state0)

    val l1 = new RecordingLogger
    val state1 = state.copy(logger = l1)
    val thread1 = mkThread(state1)

    thread0.start()
    thread1.start()
    thread0.join()
    thread1.join()

    val needle = ("info", "message")
    val messages0 = l0.getMessages()
    val messages1 = l1.getMessages()
    assertEquals(10, messages0.count(_ == needle).toLong)
    assertEquals(10, messages1.count(_ == needle).toLong)
  }

}
