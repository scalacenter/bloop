package bloop.util

import bloop.io.AbsolutePath

case class BestEffortDirs(
    buildDir: AbsolutePath,
    depDir: AbsolutePath
)
