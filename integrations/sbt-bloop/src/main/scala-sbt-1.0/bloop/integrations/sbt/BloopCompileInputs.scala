package bloop.integrations.sbt

import java.io.File
import xsbti.Reporter
import ch.epfl.scala.bsp4j.BuildTargetIdentifier

case class BloopCompileInputs(
    buildTargetId: BuildTargetIdentifier,
    //config: BloopProjectConfig,
    //reporter: Reporter,
    analysisOut: File
)
