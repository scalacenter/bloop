package bloop.bloop4j.api

import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.InitializeBuildResult

trait LowLevelBuildClientApi[F[_]] {
  def initialize(params: InitializeBuildParams): F[InitializeBuildResult]
  def compile(params: CompileParams): F[CompileResult]
  def exit: F[Unit]
}
