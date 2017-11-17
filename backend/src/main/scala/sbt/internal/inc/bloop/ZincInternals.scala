package sbt.internal.inc.bloop

import java.nio.file.{Files, Path}

import bloop.{CompilerCache, ScalaInstance}
import bloop.io.AbsolutePath
import xsbti.{ComponentProvider, GlobalLock}
import sbt.internal.inc.ZincComponentCompiler
import sbt.librarymanagement.{Configurations, ModuleID}

object ZincInternals {
  def latestVersion: String     = ZincComponentCompiler.incrementalVersion
  def getGlobalLock: GlobalLock = ZincComponentCompiler.getDefaultLock
  def getComponentProvider(componentsDir: AbsolutePath): ComponentProvider = {
    val componentsPath = componentsDir.underlying
    if (!Files.exists(componentsPath)) Files.createDirectory(componentsPath)
    ZincComponentCompiler.getDefaultComponentProvider(componentsPath.toFile())
  }

  private val CompileConf = Some(Configurations.Compile.name)
  def getModuleForBridgeSources(scalaInstance: ScalaInstance): ModuleID = {
    def compilerBridgeId(scalaVersion: String) = {
      // Defaults to bridge for 2.13 for Scala versions bigger than 2.13.x
      scalaVersion match {
        case sc if (sc startsWith "2.10.") => "compiler-bridge_2.10"
        case sc if (sc startsWith "2.11.") => "compiler-bridge_2.11"
        case sc if (sc startsWith "2.12.") => "compiler-bridge_2.12"
        case _                             => "compiler-bridge_2.13"
      }
    }

    val bridgeId = compilerBridgeId(scalaInstance.version)
    ModuleID("ch.epfl.scala", bridgeId, latestVersion).withConfigurations(CompileConf).sources()
  }

  /**
   * Returns the id for the compiler interface component.
   *
   * The ID contains the following parts:
   *   - The organization, name and revision.
   *   - The bin separator to make clear the jar represents binaries.
   *   - The Scala version for which the compiler interface is meant to.
   *   - The JVM class version.
   *
   * Example: "org.scala-sbt-compiler-bridge-1.0.0-bin_2.11.7__50.0".
   *
   * @param sources The moduleID representing the compiler bridge sources.
   * @param scalaInstance The scala instance that sets the scala version for the id.
   * @return The complete jar identifier for the bridge sources.
   */
  def getBridgeComponentId(sources: ModuleID, scalaInstance: ScalaInstance): String = {
    import ZincComponentCompiler.{binSeparator, javaClassVersion}
    val id           = s"${sources.organization}-${sources.name}-${sources.revision}"
    val scalaVersion = scalaInstance.actualVersion()
    s"$id$binSeparator${scalaVersion}__$javaClassVersion"
  }
}
