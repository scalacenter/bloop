package bloop.bloop4j.api

import scala.concurrent.Future

import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.InitializeBuildResult

import bloop.bloop4j.api.handlers.BuildClientHandlers

class ScalaLowLevelBuildClient[ClientHandlers <: BuildClientHandlers](
    underlying: NakedLowLevelBuildClient[ClientHandlers]
) extends LowLevelBuildClientApi[Future] {
  def initialize(params: InitializeBuildParams): Future[InitializeBuildResult] = ???
  def compile(params: CompileParams): Future[CompileResult] = ???
  def exit: Future[Unit] = ???
}
