package bloop.scalanative
import java.nio.file.Files
import java.nio.file.Path

import scala.scalanative.build
import scala.scalanative.util.Scope

import bloop.config.Config.LinkerMode
import bloop.config.Config.NativeConfig
import bloop.config.Config.NativeBuildTarget
import scala.scalanative.build.BuildTarget
import bloop.io.Paths
import bloop.io.AbsolutePath
import bloop.logging.DebugFilter
import bloop.logging.Logger

class NativeLinkerException(msg: String) extends RuntimeException(msg)

object NativeBridge {
  private implicit val ctx: DebugFilter = DebugFilter.Link
  private val sharedScope = Scope.unsafe()

  def nativeLink(
      config0: NativeConfig,
      workdir: Path,
      classpath: Array[Path],
      entry: Option[String],
      target: Path,
      logger: Logger
  ): Path = {
    val absWorkdir = AbsolutePath(workdir)
    if (absWorkdir.isDirectory) Paths.delete(absWorkdir)
    Files.createDirectories(workdir)

    val nativeLogger =
      build.Logger(logger.trace _, logger.debug _, logger.info _, logger.warn _, logger.error _)
    val config = setUpNativeConfig(classpath, config0)
    val nativeMode = config.mode match {
      case LinkerMode.Debug => build.Mode.debug
      case LinkerMode.Release => build.Mode.releaseFast
    }
    val nativeLTO = config.mode match {
      case LinkerMode.Debug => build.LTO.none
      case LinkerMode.Release if bloop.util.CrossPlatform.isMac => build.LTO.full
      case LinkerMode.Release => build.LTO.thin
    }

    val buildTarget = config.buildTarget
      .map(_ match {
        case NativeBuildTarget.Application => BuildTarget.application
        case NativeBuildTarget.LibraryDynamic => BuildTarget.libraryDynamic
        case NativeBuildTarget.LibraryStatic => BuildTarget.libraryStatic
      })
      .getOrElse(BuildTarget.application)

    val nativeConfig =
      build.Config.empty
        .withClassPath(classpath)
        .withWorkdir(workdir)
        .withLogger(nativeLogger)
        .withCompilerConfig(
          build.NativeConfig.empty
            .withClang(config.clang)
            .withClangPP(config.clangpp)
            .withCompileOptions(config.options.compiler)
            .withLinkingOptions(config.options.linker)
            .withGC(build.GC(config.gc))
            .withMode(nativeMode)
            .withBuildTarget(buildTarget)
            .withLTO(nativeLTO)
            .withLinkStubs(config.linkStubs)
            .withCheck(config.check)
            .withDump(config.dump)
            .withTargetTriple(config.targetTriple)
        )

    if (buildTarget == BuildTarget.application) {
      entry match {
        case None =>
          throw new NativeLinkerException("Missing main class when linking native application")
        case Some(mainClass) =>
          build.Build.build(nativeConfig.withMainClass(mainClass), target)(sharedScope)
      }
    } else build.Build.build(nativeConfig, target)(sharedScope)
  }

  private[scalanative] def setUpNativeConfig(
      classpath: Array[Path],
      config: NativeConfig
  ): NativeConfig = {
    val mode = config.mode
    val options = config.options
    val gc = if (config.gc.isEmpty) build.GC.default.name else config.gc
    val clang = if (config.clang.toString.isEmpty) build.Discover.clang() else config.clang
    val clangpp = if (config.clangpp.toString.isEmpty) build.Discover.clangpp() else config.clangpp
    val lopts = if (options.linker.isEmpty) build.Discover.linkingOptions() else options.linker
    val copts = if (options.compiler.isEmpty) build.Discover.compileOptions() else options.compiler

    val targetTriple = config.targetTriple

    NativeConfig.apply(
      version = config.version,
      mode = mode,
      toolchain = Nil, // No worries, toolchain is on this project's classpath
      gc = gc,
      targetTriple = targetTriple,
      clang = clang,
      clangpp = clangpp,
      options = options,
      linkStubs = config.linkStubs,
      check = config.check,
      dump = config.dump,
      output = config.output
    )
  }
}
