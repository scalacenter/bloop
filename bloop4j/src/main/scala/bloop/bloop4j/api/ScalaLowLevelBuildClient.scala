package bloop.bloop4j.api

import scala.concurrent.Future

import ch.epfl.scala.bsp4j.InitializeBuildResult
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.CompileParams
import bloop.bloop4j.api.handlers.BuildClientHandlers

class ScalaLowLevelBuildClient[ClientHandlers <: BuildClientHandlers](
    underlying: NakedLowLevelBuildClient[ClientHandlers]
) extends LowLevelBuildClientApi[Future] {
  def initialize: Future[InitializeBuildResult] = ???
  def compile(params: CompileParams): Future[CompileResult] = ???
  def exit: Future[Unit] = ???
}
