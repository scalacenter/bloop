package sbt.internal.inc

import _root_.bloop.{ScalaInstance => BloopScalaInstance}

import sbt.librarymanagement.ModuleID
import sbt.librarymanagement.Configurations

object HydraSupport {
  import xsbti.compile.ScalaInstance

  val bridgeVersion = sys.props.get("bloop.hydra.bridgeVersion").getOrElse("0.2.0")
  val bridgeNamePrefix =
    sys.props.get("bloop.hydra.bridgeNamePrefix").getOrElse("bloop-hydra-bridge")

  // The Hydra resolver is used to fetch both the bloop-hydra-bridge and the Hydra jars
  val resolver = {
    coursier.maven.MavenRepository(
      sys.props
        .get("bloop.hydra.resolver")
        .getOrElse("https://repo.triplequote.com/artifactory/libs-release")
    )
  }

  /* Hydra is considered enabled if the Scala instance contains the hydra scala-compiler jar. */
  def isEnabled(instance: ScalaInstance) = {
    instance match {
      case instance: BloopScalaInstance => instance.supportsHydra
      case _ => false
    }
  }

  def getModuleForBridgeSources(instance: ScalaInstance): ModuleID = {
    val compileConf = Some(Configurations.Compile.name)
    ModuleID("com.triplequote", getCompilerBridgeId(instance), bridgeVersion)
      .withConfigurations(compileConf)
      .sources()
  }

  def getCompilerBridgeId(instance: ScalaInstance) = {
    val suffix = instance.version match {
      case sc if (sc startsWith "2.11.") => "2.11"
      case sc if (sc startsWith "2.12.") => "2.12"
      case _ => "2.13"
    }
    s"${bridgeNamePrefix}_$suffix"
  }
}
