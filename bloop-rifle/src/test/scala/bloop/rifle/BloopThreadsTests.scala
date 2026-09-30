package bloop.rifle

import bloop.rifle.internal.Operations
import com.eed3si9n.expecty.Expecty.expect

import java.io.{ByteArrayOutputStream, IOException}
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.file.Files
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{CountDownLatch, ExecutorService, Executors, TimeUnit, TimeoutException}

import scala.concurrent.duration._

/**
 * A hung Nailgun call (typically `bloop about` against a silent server) must not starve the
 * `startServerChecks` pool: the pool has to keep serving other tasks, and the timed out call
 * has to release the thread it was blocking.
 */
class BloopThreadsTests extends munit.FunSuite {

  private def withThreads[T](f: BloopThreads => T): T = {
    val threads = BloopThreads.create()
    try f(threads)
    finally {
      threads.jsonrpc.shutdownNow(): Unit
      threads.startServerChecks.shutdownNow(): Unit
    }
  }

  private def withSingleThreadExecutor[T](name: String)(f: ExecutorService => T): T = {
    val executor = Executors.newSingleThreadExecutor { r =>
      val t = new Thread(r, name)
      t.setDaemon(true)
      t
    }
    try f(executor)
    finally executor.shutdownNow(): Unit
  }

  /**
   * A fake Nailgun server that accepts a single connection and only reads from it (arguments,
   * environment, command, heartbeats…), never answering. `clientClosed` is counted down once the
   * client closes the connection.
   */
  private final class SilentNailgunServer extends AutoCloseable {
    private val serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    private val connection = new AtomicReference[Socket]
    val clientConnected = new CountDownLatch(1)
    val clientClosed = new CountDownLatch(1)

    def port: Int = serverSocket.getLocalPort

    private val thread = new Thread("silent-nailgun-server") {
      setDaemon(true)
      override def run(): Unit =
        try {
          val socket = serverSocket.accept()
          connection.set(socket)
          clientConnected.countDown()
          val in = socket.getInputStream
          val buf = new Array[Byte](1024)
          try while (in.read(buf) >= 0) {}
          catch { case _: IOException => }
          clientClosed.countDown()
        } catch { case _: IOException => }
        finally close()
    }
    thread.start()

    def close(): Unit =
      try Option(connection.get()).foreach(_.close())
      finally serverSocket.close()
  }

  test("startServerChecks keeps running tasks while an earlier one is stuck") {
    withThreads { threads =>
      val release = new CountDownLatch(1)
      val stuckStarted = new CountDownLatch(1)
      val secondRan = new CountDownLatch(1)
      try {
        threads.startServerChecks.execute { () =>
          stuckStarted.countDown()
          release.await()
        }
        expect(stuckStarted.await(10, TimeUnit.SECONDS))
        threads.startServerChecks.execute(() => secondRan.countDown())
        expect(secondRan.await(2, TimeUnit.SECONDS))
      } finally release.countDown()
    }
  }

  test("shutdown shuts down both pools") {
    withThreads { threads =>
      threads.shutdown()
      expect(threads.jsonrpc.isShutdown)
      expect(threads.startServerChecks.isShutdown)
    }
  }

  test("timeout invokes the cancellation hook and rethrows") {
    withSingleThreadExecutor("operations-timeout-tests") { executor =>
      val release = new CountDownLatch(1)
      val bodyFinished = new CountDownLatch(1)
      val hookRan = new AtomicBoolean
      val followUpRan = new CountDownLatch(1)
      try {
        intercept[TimeoutException] {
          Operations.timeout(
            200.millis,
            executor,
            BloopRifleLogger.nop,
            onTimeout = {
              hookRan.set(true)
              release.countDown()
            }
          ) {
            try release.await()
            finally bodyFinished.countDown()
          }
        }
        expect(hookRan.get())
        expect(bodyFinished.await(10, TimeUnit.SECONDS))
        executor.execute(() => followUpRan.countDown())
        expect(followUpRan.await(10, TimeUnit.SECONDS))
      } finally release.countDown()
    }
  }

  test("timeout returns the body result when it completes in time") {
    withSingleThreadExecutor("operations-timeout-tests") { executor =>
      val res = Operations.timeout(2.seconds, executor, BloopRifleLogger.nop)(42)
      expect(res == 42)
    }
  }

  test("about reclaims the pool thread when the server never answers") {
    val server = new SilentNailgunServer
    val workingDir = Files.createTempDirectory("bloop-rifle-about-tests")
    try
      withSingleThreadExecutor("operations-about-tests") { scheduler =>
        intercept[TimeoutException] {
          Operations.about(
            BloopRifleConfig.Address.Tcp("127.0.0.1", server.port),
            workingDir,
            new ByteArrayOutputStream,
            new ByteArrayOutputStream,
            BloopRifleLogger.nop,
            scheduler,
            timeout = 1.second
          )
        }
        expect(server.clientConnected.getCount == 0)
        expect(server.clientClosed.await(5, TimeUnit.SECONDS))
        val followUpRan = new CountDownLatch(1)
        scheduler.execute(() => followUpRan.countDown())
        expect(followUpRan.await(5, TimeUnit.SECONDS))
      }
    finally {
      server.close()
      Files.deleteIfExists(workingDir): Unit
    }
  }
}
