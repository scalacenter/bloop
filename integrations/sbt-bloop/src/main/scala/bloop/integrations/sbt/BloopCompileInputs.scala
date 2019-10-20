package bloop.integrations.sbt

import java.io.File
import xsbti.Reporter

case class BloopCompileInputs(
    config: BloopProjectConfig,
    reporter: Reporter,
    analysisOut: File
)
