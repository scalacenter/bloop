package bloop.tasks

import org.junit.Test
import org.junit.Assert.assertEquals

import bloop.cli.Commands
import bloop.engine.{Interpreter, Run, State}
import bloop.exec.JavaEnv
import bloop.logging.RecordingLogger

class TestLoggingSpec {

  @Test
  def concurrentTestRunsHaveDifferentStreams = {
    val projectName = "with-tests"
    val moduleName = "with-tests-test"
    val state = {
      val state = ProjectHelpers.loadTestProject(projectName)
      val inProcessEnv = JavaEnv.default
      val build = state.build
      val beforeCompilation = state.copy(
        build = build.copy(projects = build.projects.map(_.copy(javaEnv = inProcessEnv))))
      val action = Run(Commands.Compile(moduleName))
      Interpreter.execute(action, beforeCompilation)
    }

    val testAction = Run(Commands.Test(moduleName))
    def mkThread(state: State) = {
      new Thread {
        override def run(): Unit = {
          Interpreter.execute(testAction, state)
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
