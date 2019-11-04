package bloop.integrations.sbt

import java.io.File

import bloop.config.Config

case class BloopProjectConfig(target: File, config: Config.File)
