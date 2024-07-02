package bloop.cli

import bloop.cli.util.CsLoggerUtil._
import coursier.cache.FileCache
import coursier.util.Task
import dependency.parser.ModuleParser
import dependency.{DependencyLike, ScalaParameters, ScalaVersion}

import java.io.File

import bloop.cli.util.Artifacts
import bloop.rifle.BloopRifleConfig

object BloopClassPath {

  def bloopClassPath(
      logger: Logger,
      cache: FileCache[Task],
      bloopVersion: String
  ): Either[Throwable, (Seq[File], Boolean)] = {

    val moduleStr = BloopRifleConfig.defaultModule

    for {
      mod <- ModuleParser
        .parse(moduleStr)
        .left
        .map(err => new Exception(s"Error parsing Bloop module '$moduleStr': $err"))
      dep = DependencyLike(mod, bloopVersion)
      sv = BloopRifleConfig.defaultScalaVersion
      sbv = ScalaVersion.binary(sv)
      params = ScalaParameters(sv, sbv)
      cp <- Artifacts.artifacts(
        Seq(dep),
        Nil,
        Some(params),
        logger,
        cache.withMessage(s"Downloading compilation server ${dep.version}")
      )
      isScalaCliBloop = moduleStr.startsWith(BloopRifleConfig.scalaCliBloopOrg + ":")
    } yield (cp.map(_._2.toIO), isScalaCliBloop)
  }
}
