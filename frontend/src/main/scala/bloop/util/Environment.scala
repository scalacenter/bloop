package bloop.util

import bloop.io.AbsolutePath

object Environment {
  def defaultBloopDirectory: AbsolutePath = AbsolutePath.homeDirectory.resolve(".bloop")
}
