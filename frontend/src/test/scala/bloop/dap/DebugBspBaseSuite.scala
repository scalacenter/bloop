package bloop.dap

import scala.collection.mutable
import scala.concurrent.Promise

import ch.epfl.scala.bsp

import bloop.TestSchedulers
import bloop.bsp.BloopBspDefinitions.BloopExtraBuildParams
import bloop.bsp.BspBaseSuite
import bloop.cli.BspProtocol
import bloop.cli.Commands
import bloop.engine.State
import bloop.io.AbsolutePath
import bloop.logging.BspClientLogger

import monix.execution.Scheduler

abstract class DebugBspBaseSuite extends BspBaseSuite {
  protected val debugDefaultScheduler: Scheduler =
    TestSchedulers.async("debug-bsp-default", threads = 5)

  override def protocol: BspProtocol = if (isWindows) BspProtocol.Tcp else BspProtocol.Local

  override def openBspConnectionUnsafe[T](
      state: State,
      cmd: Commands.ValidatedBsp,
      configDirectory: AbsolutePath,
      logger: BspClientLogger[_],
      userIOScheduler: Option[Scheduler] = None,
      userComputationScheduler: Option[Scheduler] = None,
      clientName: String = "test-bloop-client",
      bloopExtraParams: BloopExtraBuildParams = BloopExtraBuildParams.empty,
      compileStartPromises: Option[mutable.HashMap[bsp.BuildTargetIdentifier, Promise[Unit]]] = None
  ): UnmanagedBspTestState = {
    val ioScheduler = userIOScheduler.orElse(Some(debugDefaultScheduler))
    super.openBspConnectionUnsafe(
      state,
      cmd,
      configDirectory,
      logger,
      ioScheduler,
      userComputationScheduler,
      clientName,
      bloopExtraParams,
      compileStartPromises
    )
  }
}
