package bloop.integrations.sbt

import sbt.Logger
import java.io.File
import xsbti.Reporter
import ch.epfl.scala.bsp4j.BuildTargetIdentifier

case class BloopCompileInputs(
    buildTargetId: BuildTargetIdentifier,
    config: Option[File],
    reporter: Reporter,
    logger: Logger
)
