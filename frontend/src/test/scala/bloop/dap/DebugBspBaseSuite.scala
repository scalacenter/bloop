package bloop.dap

import bloop.TestSchedulers
import bloop.bsp.BspBaseSuite
import bloop.cli.{BspProtocol, Commands}
import bloop.engine.State
import bloop.io.AbsolutePath
import bloop.logging.BspClientLogger
import bloop.bsp.BloopBspDefinitions.BloopExtraBuildParams

import monix.execution.Scheduler

import ch.epfl.scala.bsp

import scala.collection.mutable
import scala.concurrent.Promise

abstract class DebugBspBaseSuite extends BspBaseSuite {
  protected val debugDefaultScheduler: Scheduler =
    TestSchedulers.async("debug-bsp-default", threads = 5)
  override def protocol: BspProtocol = BspProtocol.Local

  override def openBspConnection[T](
      state: State,
      cmd: Commands.ValidatedBsp,
      configDirectory: AbsolutePath,
      logger: BspClientLogger[_],
      allowError: Boolean = false,
      userIOScheduler: Option[Scheduler] = None,
      userComputationScheduler: Option[Scheduler] = None,
      clientClassesRootDir: Option[AbsolutePath] = None,
      clientName: String = "test-bloop-client",
      bloopExtraParams: BloopExtraBuildParams = BloopExtraBuildParams.empty,
      compileStartPromises: Option[mutable.HashMap[bsp.BuildTargetIdentifier, Promise[Unit]]] = None
  ): UnmanagedBspTestState = {
    val ioScheduler = userIOScheduler.orElse(Some(debugDefaultScheduler))
    super.openBspConnection(
      state,
      cmd,
      configDirectory,
      logger,
      allowError,
      ioScheduler,
      userComputationScheduler,
      clientClassesRootDir,
      clientName,
      bloopExtraParams,
      compileStartPromises
    )
  }
}
