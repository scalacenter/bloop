package bloop.bloop4j.api

import ch.epfl.scala.bsp4j.InitializeBuildResult
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileResult

trait LowLevelBuildClientApi[F[_]] {
  def initialize: F[InitializeBuildResult]
  def compile(params: CompileParams): F[CompileResult]
  def exit: F[Unit]

}
