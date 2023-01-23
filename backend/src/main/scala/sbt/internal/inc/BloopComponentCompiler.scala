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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import _root_.bloop.io.AbsolutePath
import _root_.bloop.logging.DebugFilter
import _root_.bloop.logging.{Logger => BloopLogger}
import _root_.bloop.{DependencyResolution => BloopDependencyResolution}
import _root_.bloop.{ScalaInstance => BloopScalaInstance}
import sbt.internal.inc.classpath.ClasspathUtil
import sbt.io.IO
import sbt.librarymanagement._
import xsbti.ArtifactInfo._
import xsbti.ComponentProvider
import xsbti.Logger
import xsbti.compile.ClasspathOptionsUtil
import xsbti.compile.CompilerBridgeProvider

object BloopComponentCompiler {
  import xsbti.compile.ScalaInstance

  final val binSeparator = "-bin_"
  final val javaClassVersion: String = System.getProperty("java.class.version")

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
        case sc if (sc startsWith "3.") => "scala3-sbt-bridge"
        case sc if (sc startsWith "2.10.") => "compiler-bridge_2.10"
        case sc if (sc startsWith "2.11.") => "compiler-bridge_2.11"
        case sc if (sc startsWith "2.12.") => "compiler-bridge_2.12"
        case _ => "compiler-bridge_2.13"
      }
    }

    val (isDotty, organization, version) = scalaInstance match {
      case instance: BloopScalaInstance if instance.isDotty =>
        (true, instance.organization, instance.version)
      case _ => (false, "org.scala-sbt", latestVersion)
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
      logger: BloopLogger
  ) extends CompilerBridgeProvider {

    /**
     * Defines a richer interface for Scala users that want to pass in an explicit module id.
     *
     * Note that this method cannot be defined in [[CompilerBridgeProvider]] because [[ModuleID]]
     * is a Scala-defined class to which the compiler bridge cannot depend on.
     */
    private def compiledBridge(bridgeSources: ModuleID, scalaInstance: ScalaInstance): File = {
      val raw = new RawCompiler(scalaInstance, ClasspathOptionsUtil.auto, logger)
      val bridgeJarsOpt = scalaInstance match {
        case b: _root_.bloop.ScalaInstance => b.bridgeJarsOpt
        case _ => None
      }
      val zinc = new BloopComponentCompiler(raw, manager, bridgeSources, bridgeJarsOpt, logger)
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
          )
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
      val loaderLibraryOnly = ClasspathUtil.toLoader(Vector(scalaLibrary.toPath()))
      val jarsToLoad2 = jarsToLoad.toVector.filterNot(_ == scalaLibrary)
      val loader = ClasspathUtil.toLoader(jarsToLoad2.map(_.toPath()), loaderLibraryOnly)
      val properties = ResourceLoader.getSafePropertiesFor("compiler.properties", loader)
      val loaderVersion = Option(properties.getProperty("version.number"))
      val scalaV = loaderVersion.getOrElse("unknown")
      new inc.ScalaInstance(
        scalaV,
        loader,
        loader,
        loaderLibraryOnly,
        Array(scalaLibrary),
        jarsToLoad,
        jarsToLoad,
        loaderVersion
      )
    }
  }

  def interfaceProvider(
      compilerBridgeSource: ModuleID,
      manager: BloopComponentManager,
      logger: BloopLogger
  ): CompilerBridgeProvider = {
    val bridgeSources = Some(compilerBridgeSource)
    new BloopCompilerBridgeProvider(
      bridgeSources,
      manager,
      logger
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
    bridgeJarsOpt: Option[Seq[File]],
    logger: BloopLogger
) {
  implicit val debugFilter: DebugFilter.Compilation.type = DebugFilter.Compilation
  def compiledBridgeJar: File = {
    val jarBinaryName = {
      val instance = compiler.scalaInstance
      val bridgeName = {
        if (!HydraSupport.isEnabled(instance)) bridgeSources
        else HydraSupport.getModuleForBridgeSources(instance)
      }

      BloopComponentCompiler.getBridgeComponentId(bridgeName, compiler.scalaInstance)
    }
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
      IO.withTemporaryDirectory { _ =>
        val shouldResolveSources =
          bridgeSources.explicitArtifacts.exists(_.`type` == "src")
        val allArtifacts = bridgeJarsOpt.map(_.map(_.toPath)).getOrElse {
          BloopDependencyResolution.resolveWithErrors(
            List(
              BloopDependencyResolution
                .Artifact(bridgeSources.organization, bridgeSources.name, bridgeSources.revision)
            ),
            logger,
            resolveSources = shouldResolveSources
          ) match {
            case Right(paths) => paths.map(_.underlying).toVector
            case Left(t) =>
              val msg = s"Couldn't retrieve module $bridgeSources"
              throw new InvalidComponent(msg, t)
          }
        }

        if (!shouldResolveSources) {
          // This is usually true in the Dotty case, that has a pre-compiled compiler
          manager.define(compilerBridgeId, allArtifacts.map(_.toFile()))
        } else {
          val (sources, xsbtiJars) =
            allArtifacts.partition(_.toFile.getName.endsWith("-sources.jar"))
          val (toCompileID, allSources) = {
            if (!HydraSupport.isEnabled(compiler.scalaInstance)) (bridgeSources.name, sources)
            else {
              val hydraBridgeModule = HydraSupport.getModuleForBridgeSources(compiler.scalaInstance)
              mergeBloopAndHydraBridges(sources.toVector, hydraBridgeModule) match {
                case Right(mergedHydraBridgeSourceJar) =>
                  (hydraBridgeModule.name, mergedHydraBridgeSourceJar)
                case Left(error) =>
                  logger.error(
                    s"Unexpected error when merging Bloop and Hydra bridges. Reason: $error"
                  )
                  logger.trace(error)
                  // Throw, there's no reasonable fallback method if Hydra sources fail to be merged
                  throw error
              }
            }
          }

          AnalyzingCompiler.compileSources(
            allSources,
            target.toPath(),
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

  private def mergeBloopAndHydraBridges(
      bloopBridgeSourceJars: Vector[Path],
      hydraBridgeModule: ModuleID
  ): Either[InvalidComponent, Vector[Path]] = {
    val hydraSourcesJars = BloopDependencyResolution.resolveWithErrors(
      List(
        BloopDependencyResolution
          .Artifact(
            hydraBridgeModule.organization,
            hydraBridgeModule.name,
            hydraBridgeModule.revision
          )
      ),
      logger,
      resolveSources = true,
      additionalRepositories = List(HydraSupport.resolver)
    ) match {
      case Right(paths) => Right(paths.map(_.underlying).toVector)
      case Left(t) =>
        val msg = s"Couldn't retrieve module $hydraBridgeModule"
        Left(new InvalidComponent(msg, t))
    }

    import sbt.io.IO.{zip, unzip, withTemporaryDirectory, relativize}
    hydraSourcesJars match {
      case Right(sourceJar +: otherJars) =>
        if (otherJars.nonEmpty) {
          logger.warn(
            s"Expected to retrieve a single bridge jar for Hydra. Keeping $sourceJar and ignoring $otherJars"
          )
        }

        bloopBridgeSourceJars.headOption match {
          case None =>
            logger.debug("Missing stock compiler bridge, proceeding with all Hydra bridge sources.")
          case Some(stockCompilerBridge) =>
            logger.debug(s"Merging ${stockCompilerBridge} with $sourceJar")
        }

        withTemporaryDirectory { tempDir =>
          val hydraSourceContents = unzip(sourceJar.toFile, tempDir)
          logger.debug(s"Sources from hydra bridge: $hydraSourceContents")

          // Unfortunately we can only use names to filter out, let's hope there's no clashes
          val filterOutConflicts = new sbt.io.NameFilter {
            val hydraNameDenylist = hydraSourceContents.map(_.getName)
            def accept(rawPath: String): Boolean = {
              val path = Paths.get(rawPath)
              val fileName = path.getFileName.toString
              !hydraNameDenylist.contains(fileName)
            }
          }

          // Extract bridge source contens in same folder with Hydra contents having preference
          val regularSourceContents = bloopBridgeSourceJars.foldLeft(Set.empty[File]) {
            case (extracted, sourceJar) =>
              extracted ++ unzip(sourceJar.toFile(), tempDir, filter = filterOutConflicts)
          }

          logger.debug(s"Sources from bloop bridge: $regularSourceContents")

          val mergedJar = Files.createTempFile(HydraSupport.bridgeNamePrefix, "merged")
          logger.debug(s"Merged jar destination: $mergedJar")
          val allSourceContents =
            (hydraSourceContents ++ regularSourceContents).map(s => s -> relativize(tempDir, s).get)

          zip(allSourceContents.toSeq, mergedJar.toFile(), time = None)
          Right(Vector(mergedJar))
        }

      case Right(Seq()) =>
        val msg =
          s"""Hydra resolution failure: bridge source jar is empty!
             |  -> Hydra coordinates ($hydraBridgeModule)
             |  -> Report this error to support@triplequote.com.
            """.stripMargin
        Left(new InvalidComponent(msg))

      case error @ Left(_) => error
    }
  }
}
