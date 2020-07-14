package bloop.exec

import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.file.FileSystems
import java.util.concurrent.TimeUnit

import bloop.io.AbsolutePath
import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess, NuProcessBuilder}
import monix.eval.Task

/**
 * A wrapper class for NuProcess. The main purpose of it is to provide
 * a way to kill the process with all its children. The `destroy` method
 * of NuProcess kills only the main process, so the child processes are re-parented
 * and they continue to run.
 *
 * In order to kill the process with it's children, we need to kill the whole *process group*.
 * Unfortunately, the process spawned via `NuProcess` is assigned to the same process group as
 * the bloop process, so we have no way to kill it selectively. This is why, we need to run the
 * process with `setsid` prefix command. It creates the new process group, which will contain only
 * the process and its children. The Process Group ID (PGID) is equal to the root process PID.
 *
 * In order to kill the process group, the `kill` syscall must be called with negative number equal to -PGID
 *
 * References:
 * https://en.wikipedia.org/wiki/Orphan_process
 *
 */
class Process(nuProcess: NuProcess) {
  import Process._

  def destroy(): Int = {
    if (isPosix) {
      import com.zaxxer.nuprocess.internal.LibC
      if (nuProcess.isRunning) LibC.kill(-nuProcess.getPID, LibC.SIGTERM) // negative pid means "Kill process group"
      nuProcess.waitFor(200, TimeUnit.MILLISECONDS)
      if (nuProcess.isRunning) LibC.kill(-nuProcess.getPID, LibC.SIGKILL)
      nuProcess.waitFor(200, TimeUnit.MILLISECONDS)
    } else {
      nuProcess.destroy(false)
      nuProcess.waitFor(200, TimeUnit.MILLISECONDS)
      nuProcess.destroy(true)
      nuProcess.waitFor(200, TimeUnit.MILLISECONDS)
    }
  }

  def getPID: Int = nuProcess.getPID

  def isRunning = nuProcess.isRunning

  def closeStdin(force: Boolean): Unit = nuProcess.closeStdin(force: Boolean)

  def waitFor(timeout: Long, timeUnit: TimeUnit): Int = nuProcess.waitFor(timeout, timeUnit)

  def writeStdin(buffer: ByteBuffer): Unit = nuProcess.writeStdin(buffer)
}

object Process {
  def isPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

  /**
   * Runs `cmd` in a new process and logs the results. The exit code is returned
   *
   * @param cwd    The directory in which to start the process
   * @param cmd    The command to run
   * @param env   The options to run the program with
   * @return The exit code of the process
   */
  def run(
      cwd: AbsolutePath,
      cmd: Seq[String],
      handler: NuAbstractProcessHandler,
      env: Map[String, String]
  ): Task[Process] = {
    import scala.collection.JavaConverters._
    if (cwd.exists) {
      val builder = new NuProcessBuilder((processGroupPrefix ++ cmd).asJava, env.asJava)

      builder.setProcessListener(handler)
      builder.setCwd(cwd.underlying)
      Task(new Process(builder.start()))
    } else {
      val message = s"Working directory '$cwd' does not exist"
      Task.raiseError(new FileNotFoundException(message))
    }
  }

  private def processGroupPrefix: List[String] = if (isPosix) List("setsid") else List.empty
}
