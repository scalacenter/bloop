package scala.build.bloop

import ch.epfl.scala.bsp4j

trait BuildServer extends bsp4j.BuildServer with bsp4j.ScalaBuildServer with bsp4j.JavaBuildServer
    with bsp4j.JvmBuildServer
