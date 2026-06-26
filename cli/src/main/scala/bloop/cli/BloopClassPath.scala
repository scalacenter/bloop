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
  ): Either[Throwable, Seq[File]] = {

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
      // Include local Ivy and Maven repositories so locally published SNAPSHOTs are found
      localRepos = Seq(
        "ivy2Local", // ~/.ivy2/local
        "m2Local" // ~/.m2/repository
      )
      remoteRepos = Seq("https://central.sonatype.com/repository/maven-snapshots")
      cp <- Artifacts.artifacts(
        Seq(dep),
        localRepos ++ remoteRepos,
        Some(params),
        logger,
        cache.withMessage(s"Downloading compilation server ${dep.version}")
      )
    } yield cp.map(_._2.toIO)
  }
}
