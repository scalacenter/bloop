package bloop.bloop4j.api

import scala.concurrent.Future

import ch.epfl.scala.bsp4j.InitializeBuildResult
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.CompileParams

class ScalaLowLevelBuildClient(underlying: NakedLowLevelBuildClient)
    extends LowLevelBuildClientApi[Future] {
  def initialize: Future[InitializeBuildResult] = ???
  def compile(params: CompileParams): Future[CompileResult] = ???
  def exit: Future[Unit] = ???
}
