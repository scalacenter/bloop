package bloop.cli.util

import coursier.cache.FileCache
import coursier.parse.RepositoryParser
import coursier.util.Task
import coursier.{Fetch, moduleString}
import dependency.{AnyDependency, ScalaParameters}

import bloop.cli.util.CoursierUtils._
import bloop.cli.Logger

object Artifacts {

  final class NoScalaVersionProvidedError(
      val depOrModule: Either[dependency.AnyModule, dependency.AnyDependency]
  ) extends Exception(
        {
          val str = depOrModule match {
            case Left(mod) => "module " + mod.render
            case Right(dep) => "dependency " + dep.render
          }
          s"Got Scala $str, but no Scala version is provided"
        }
      )

  def artifacts(
      dependencies: Seq[AnyDependency],
      extraRepositories: Seq[String],
      paramsOpt: Option[ScalaParameters],
      logger: Logger,
      cache: FileCache[Task],
      classifiersOpt: Option[Set[String]] = None
  ): Either[Throwable, Seq[(String, os.Path)]] =
    fetch(dependencies, extraRepositories, paramsOpt, logger, cache, classifiersOpt).map { res =>
      val result = res.artifacts.iterator.map { case (a, f) => (a.url, os.Path(f, os.pwd)) }.toList
      logger.debug {
        val elems = Seq(s"Found ${result.length} artifacts:") ++
          result.map("  " + _._2) ++
          Seq("")
        elems.mkString(System.lineSeparator())
      }
      result
    }

  private def fetch(
      dependencies: Seq[AnyDependency],
      extraRepositories: Seq[String],
      paramsOpt: Option[ScalaParameters],
      logger: Logger,
      cache: FileCache[Task],
      classifiersOpt: Option[Set[String]]
  ): Either[Throwable, Fetch.Result] = {
    val maybeCoursierDependencies = {
      val seq = dependencies
        .map { dep =>
          val maybeUrl = dep.getUserParam("url")
          if (maybeUrl.nonEmpty)
            sys.error("unsupported")
          dep
        }
        .map(dep => dep.toCs(paramsOpt))
        .flatMap {
          case Left(e: NoScalaVersionProvidedError) => Some(Left(e))
          case Left(_) => None
          case Right(dep) => Some(Right(dep))
        }

      val errors = seq.collect {
        case Left(e) => e
      }
      if (errors.isEmpty)
        Right(seq.collect { case Right(d) => d })
      else
        Left(???)
    }

    for {
      coursierDependencies <- maybeCoursierDependencies
      res <- fetch0(
        coursierDependencies,
        extraRepositories,
        paramsOpt.map(_.scalaVersion),
        Nil,
        logger,
        cache,
        classifiersOpt
      )
    } yield res
  }

  private def fetch0(
      dependencies: Seq[coursier.Dependency],
      extraRepositories: Seq[String],
      forceScalaVersionOpt: Option[String],
      forcedVersions: Seq[(coursier.Module, String)],
      logger: Logger,
      cache: FileCache[Task],
      classifiersOpt: Option[Set[String]]
  ): Either[Throwable, Fetch.Result] = {
    logger.debug {
      s"Fetching $dependencies" +
        (if (extraRepositories.isEmpty) "" else s", adding $extraRepositories")
    }

    val forceScalaVersions = forceScalaVersionOpt match {
      case None => Nil
      case Some(sv) =>
        if (sv.startsWith("2."))
          Seq(
            mod"org.scala-lang:scala-library" -> sv,
            mod"org.scala-lang:scala-compiler" -> sv,
            mod"org.scala-lang:scala-reflect" -> sv
          )
        else
          // FIXME Shouldn't we force the org.scala-lang:scala-library version too?
          // (to a 2.13.x version)
          Seq(
            mod"org.scala-lang:scala3-library_3" -> sv,
            mod"org.scala-lang:scala3-compiler_3" -> sv,
            mod"org.scala-lang:scala3-interfaces_3" -> sv,
            mod"org.scala-lang:scala3-tasty-inspector_3" -> sv,
            mod"org.scala-lang:tasty-core_3" -> sv
          )
    }

    val forceVersion = forceScalaVersions ++ forcedVersions

    for {
      extraRepositories0 <-
        RepositoryParser
          .repositories(extraRepositories)
          .either
          .left
          .map(errors => new Exception(s"Error parsing repositories: ${errors.mkString(", ")}"))
      // FIXME Many parameters that we could allow to customize here
      fetcher = {
        var fetcher0 = coursier
          .Fetch()
          .withCache(cache)
          .addRepositories(extraRepositories0: _*)
          .addDependencies(dependencies: _*)
          .mapResolutionParams(_.addForceVersion(forceVersion: _*))
        for (classifiers <- classifiersOpt) {
          if (classifiers("_"))
            fetcher0 = fetcher0.withMainArtifacts()
          fetcher0 = fetcher0
            .addClassifiers(classifiers.toSeq.filter(_ != "_").map(coursier.Classifier(_)): _*)
        }
        fetcher0
      }
      res <- cache.logger.use {
        fetcher.eitherResult()
      }
    } yield res
  }
}
