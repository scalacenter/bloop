package bloop

import java.net.URI

import bloop.io.AbsolutePath
import bloop.logging.Logger
import coursier.maven.MavenRepository

import scala.concurrent.ExecutionContext
import scala.tools.nsc.Properties

object HydraCompileSpec extends BaseCompileSpec {
  override protected val TestProject = HydraTestProject
  override protected def extraCompilationMessageOutput: String =
    " [E-1] Using 1 Hydra worker to compile Scala sources."

  private val TriplequoteResolver = MavenRepository(
    "https://repo.triplequote.com/artifactory/libs-release/"
  )
  private val HydraVersion = "2.1.13"

  object HydraTestProject extends bloop.util.BaseTestProject {
    override protected def mkScalaInstance(
        scalaOrg: String,
        scalaName: String,
        scalaVersion: Option[String],
        allJars: Seq[AbsolutePath],
        logger: Logger
    )(implicit ec: ExecutionContext): ScalaInstance = {
      val version = scalaVersion.getOrElse(Properties.versionNumberString)
      val allPaths = DependencyResolution.resolve(
        "com.triplequote",
        s"hydra_$version",
        HydraVersion,
        logger,
        resolveSources = false,
        additionalRepos = Seq(TriplequoteResolver)
      )
      val allJars = allPaths.collect {
        case path if path.underlying.toString.endsWith(".jar") => path.underlying.toFile
      }
      ScalaInstance(scalaOrg, scalaName, version, allJars.map(AbsolutePath.apply), logger)
    }
  }
}
