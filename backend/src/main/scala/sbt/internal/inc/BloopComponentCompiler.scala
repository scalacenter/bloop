/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt
package internal
package inc

import java.io.File
import java.util.concurrent.Callable

import sbt.internal.inc.classpath.ClasspathUtilities
import sbt.io.IO
import sbt.internal.librarymanagement._
import sbt.internal.util.{BufferedLogger, FullLogger}
import sbt.librarymanagement._
import sbt.librarymanagement.syntax._
import sbt.util.InterfaceUtil.{toSupplier => f0}
import xsbti.ArtifactInfo._
import xsbti.{ComponentProvider, GlobalLock, Logger}
import xsbti.compile.{ClasspathOptionsUtil, CompilerBridgeProvider}
import _root_.bloop.io.AbsolutePath
import _root_.bloop.{ScalaInstance => BloopScalaInstance}
import java.nio.file.Files

import _root_.bloop.logging.{Logger => BloopLogger}
import _root_.bloop.{DependencyResolution => BloopDependencyResolution}
import _root_.bloop.logging.DebugFilter
import scala.concurrent.ExecutionContext

object BloopComponentCompiler {
  import xsbti.compile.ScalaInstance

  final val binSeparator = "-bin_"
  final val javaClassVersion = System.getProperty("java.class.version")

  final lazy val latestVersion: String = BloopComponentManager.version
  def getComponentProvider(componentsDir: AbsolutePath): ComponentProvider = {
    val componentsPath = componentsDir.underlying
    if (!Files.exists(componentsPath)) Files.createDirectory(componentsPath)
    getDefaultComponentProvider(componentsPath.toFile())
  }

  private val CompileConf = Some(Configurations.Compile.name)
  def getModuleForBridgeSources(scalaInstance: ScalaInstance): ModuleID = {
    def compilerBridgeId(scalaVersion: String) = {
      // Defaults to bridge for 2.13 for Scala versions bigger than 2.13.x
      scalaVersion match {
        case sc if (sc startsWith "0.") => "dotty-sbt-bridge"
        case sc if (sc startsWith "2.10.") => "compiler-bridge_2.10"
        case sc if (sc startsWith "2.11.") => "compiler-bridge_2.11"
        case sc if (sc startsWith "2.12.") => "compiler-bridge_2.12"
        case _ => "compiler-bridge_2.13"
      }
    }

    val (isDotty, organization, version) = scalaInstance match {
      case instance: BloopScalaInstance =>
        if (instance.isDotty) (true, instance.organization, instance.version)
        else (false, "ch.epfl.scala", latestVersion)
      case instance: ScalaInstance => (false, "ch.epfl.scala", latestVersion)
    }

    val bridgeId = compilerBridgeId(scalaInstance.version)
    val module = ModuleID(organization, bridgeId, version).withConfigurations(CompileConf)
    if (isDotty) module else module.sources()
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
   *
   * @param sources The moduleID representing the compiler bridge sources.
   * @param scalaInstance The scala instance that sets the scala version for the id.
   * @return The complete jar identifier for the bridge sources.
   */
  def getBridgeComponentId(sources: ModuleID, scalaInstance: ScalaInstance): String = {
    val id = s"${sources.organization}-${sources.name}-${sources.revision}"
    val scalaVersion = scalaInstance.actualVersion()
    s"$id$binSeparator${scalaVersion}__$javaClassVersion"
  }

  /** Defines the internal implementation of a bridge provider. */
  private class BloopCompilerBridgeProvider(
      userProvidedBridgeSources: Option[ModuleID],
      manager: BloopComponentManager,
      scalaJarsTarget: File,
      logger: BloopLogger,
      scheduler: ExecutionContext
  ) extends CompilerBridgeProvider {

    /**
     * Defines a richer interface for Scala users that want to pass in an explicit module id.
     *
     * Note that this method cannot be defined in [[CompilerBridgeProvider]] because [[ModuleID]]
     * is a Scala-defined class to which the compiler bridge cannot depend on.
     */
    private def compiledBridge(bridgeSources: ModuleID, scalaInstance: ScalaInstance): File = {
      val raw = new RawCompiler(scalaInstance, ClasspathOptionsUtil.auto, logger)
      val zinc = new BloopComponentCompiler(raw, manager, bridgeSources, logger, scheduler)
      logger.debug(s"Getting $bridgeSources for Scala ${scalaInstance.version}")(
        DebugFilter.Compilation
      )
      zinc.compiledBridgeJar
    }

    override def fetchCompiledBridge(scalaInstance: ScalaInstance, logger: Logger): File = {
      val bridgeSources =
        userProvidedBridgeSources.getOrElse(getModuleForBridgeSources(scalaInstance))
      compiledBridge(bridgeSources, scalaInstance)
    }

    private case class ScalaArtifacts(compiler: File, library: File, others: Vector[File])

    private def getScalaArtifacts(scalaVersion: String, logger: BloopLogger): ScalaArtifacts = {
      def isPrefixedWith(artifact: File, prefix: String) = artifact.getName.startsWith(prefix)
      val allArtifacts = {
        BloopDependencyResolution
          .resolve(
            List(
              BloopDependencyResolution.Artifact(ScalaOrganization, ScalaCompilerID, scalaVersion)
            ),
            logger
          )(scheduler)
          .map(_.toFile)
          .toVector
      }
      logger.debug(s"Resolved scala artifacts for $scalaVersion: $allArtifacts")(
        DebugFilter.Compilation
      )
      val isScalaCompiler = (f: File) => isPrefixedWith(f, "scala-compiler-")
      val isScalaLibrary = (f: File) => isPrefixedWith(f, "scala-library-")
      val maybeScalaCompiler = allArtifacts.find(isScalaCompiler)
      val maybeScalaLibrary = allArtifacts.find(isScalaLibrary)
      val others = allArtifacts.filterNot(a => isScalaCompiler(a) || isScalaLibrary(a))
      val scalaCompilerJar = maybeScalaCompiler.getOrElse(throw MissingScalaJar.compiler)
      val scalaLibraryJar = maybeScalaLibrary.getOrElse(throw MissingScalaJar.library)
      ScalaArtifacts(scalaCompilerJar, scalaLibraryJar, others)
    }

    override def fetchScalaInstance(scalaVersion: String, unusedLogger: Logger): ScalaInstance = {
      val scalaArtifacts = getScalaArtifacts(scalaVersion, logger)

      val scalaCompiler = scalaArtifacts.compiler
      val scalaLibrary = scalaArtifacts.library
      val jarsToLoad = (scalaCompiler +: scalaLibrary +: scalaArtifacts.others).toArray
      assert(jarsToLoad.forall(_.exists), "One or more jar(s) in the Scala instance do not exist.")
      val loaderLibraryOnly = ClasspathUtilities.toLoader(Vector(scalaLibrary))
      val jarsToLoad2 = jarsToLoad.toVector.filterNot(_ == scalaLibrary)
      val loader = ClasspathUtilities.toLoader(jarsToLoad2, loaderLibraryOnly)
      val properties = ResourceLoader.getSafePropertiesFor("compiler.properties", loader)
      val loaderVersion = Option(properties.getProperty("version.number"))
      val scalaV = loaderVersion.getOrElse("unknown")
      new inc.ScalaInstance(
        scalaV,
        loader,
        loaderLibraryOnly,
        scalaLibrary,
        scalaCompiler,
        jarsToLoad,
        loaderVersion
      )
    }
  }

  def interfaceProvider(
      compilerBridgeSource: ModuleID,
      manager: BloopComponentManager,
      scalaJarsTarget: File,
      logger: BloopLogger,
      scheduler: ExecutionContext
  ): CompilerBridgeProvider = {
    val bridgeSources = Some(compilerBridgeSource)
    new BloopCompilerBridgeProvider(
      bridgeSources,
      manager,
      scalaJarsTarget,
      logger,
      scheduler
    )
  }

  /** Defines a default component provider that manages the component in a given directory. */
  final class DefaultComponentProvider(targetDir: File) extends ComponentProvider {
    import sbt.io.syntax._
    private val LockFile = targetDir / "lock"
    override def lockFile(): File = LockFile
    override def componentLocation(id: String): File = targetDir / id
    override def component(componentID: String): Array[File] =
      IO.listFiles(targetDir / componentID)
    override def defineComponent(componentID: String, files: Array[File]): Unit =
      files.foreach(f => IO.copyFile(f, targetDir / componentID / f.getName))
    override def addToComponent(componentID: String, files: Array[File]): Boolean = {
      defineComponent(componentID, files)
      true
    }
  }

  def getDefaultComponentProvider(targetDir: File): ComponentProvider = {
    require(targetDir.isDirectory)
    new DefaultComponentProvider(targetDir)
  }

}

/**
 * Component compiler which is able to to retrieve the compiler bridge sources
 * `sourceModule` using a `DependencyResolution` instance.
 * The compiled classes are cached using the provided component manager according
 * to the actualVersion field of the RawCompiler.
 */
private[inc] class BloopComponentCompiler(
    compiler: RawCompiler,
    manager: BloopComponentManager,
    bridgeSources: ModuleID,
    logger: BloopLogger,
    scheduler: ExecutionContext
) {
  def compiledBridgeJar: File = {
    val jarBinaryName =
      BloopComponentCompiler.getBridgeComponentId(bridgeSources, compiler.scalaInstance)
    manager.file(jarBinaryName)(IfMissing.define(true, compileAndInstall(jarBinaryName)))
  }

  /**
   * Resolves the compiler bridge sources, compiles them and installs the sbt component
   * in the local filesystem to make sure that it's reused the next time is required.
   *
   * @param compilerBridgeId The identifier for the compiler bridge sources.
   */
  private def compileAndInstall(compilerBridgeId: String): Unit = {
    IO.withTemporaryDirectory { binaryDirectory =>
      val target = new File(binaryDirectory, s"$compilerBridgeId.jar")
      IO.withTemporaryDirectory { retrieveDirectory =>
        import coursier.core.Type
        val resolveSources = bridgeSources.explicitArtifacts.exists(_.`type` == Type.source.value)
        val allArtifacts = BloopDependencyResolution.resolveWithErrors(
          List(
            BloopDependencyResolution
              .Artifact(bridgeSources.organization, bridgeSources.name, bridgeSources.revision)
          ),
          logger,
          resolveSources = resolveSources
        )(scheduler) match {
          case Right(paths) => paths.map(_.toFile).toVector
          case Left(t) =>
            val msg = s"Couldn't retrieve module $bridgeSources"
            throw new InvalidComponent(msg, t)
        }

        if (!resolveSources) {
          manager.define(compilerBridgeId, allArtifacts)
        } else {
          val (srcs, xsbtiJars) = allArtifacts.partition(_.getName.endsWith("-sources.jar"))
          val toCompileID = bridgeSources.name
          AnalyzingCompiler.compileSources(
            srcs,
            target,
            xsbtiJars,
            toCompileID,
            compiler,
            logger
          )
          manager.define(compilerBridgeId, Seq(target))
        }
      }
    }
  }
}
