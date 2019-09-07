package bloop.scalanative
import bloop.config.Config.{LinkerMode, NativeConfig}
import bloop.io.Paths
import bloop.logging.{DebugFilter, Logger}
import java.nio.file.{Files, Path}

import bloop.data.Project

import scala.scalanative.build.{Build, Config, Discover, GC, Mode, Logger => NativeLogger}

object NativeBridge {
  private implicit val ctx: DebugFilter = DebugFilter.Link
  def nativeLink(
      config0: NativeConfig,
      project: Project,
      classpath: Array[Path],
      entry: String,
      target: Path,
      logger: Logger
  ): Path = {
    val workdir = project.out.resolve("native")
    if (workdir.isDirectory) Paths.delete(workdir)
    Files.createDirectories(workdir.underlying)

    val nativeLogger = NativeLogger(logger.debug _, logger.info _, logger.warn _, logger.error _)
    val config = setUpNativeConfig(project, classpath, config0)
    val nativeMode = config.mode match {
      case LinkerMode.Debug => Mode.debug
      case LinkerMode.Release => Mode.release
    }

    val nativeConfig =
      Config.empty
        .withGC(GC(config.gc))
        .withMode(nativeMode)
        .withClang(config.clang)
        .withClangPP(config.clangpp)
        .withLinkingOptions(config.options.linker)
        .withCompileOptions(config.options.compiler)
        .withTargetTriple(config.targetTriple)
        .withNativelib(config.nativelib)
        .withLinkStubs(config.linkStubs)
        .withMainClass(entry)
        .withClassPath(classpath)
        .withWorkdir(workdir.underlying)
        .withLogger(nativeLogger)

    Build.build(nativeConfig, target)
  }

  private[scalanative] def setUpNativeConfig(
      project: Project,
      classpath: Array[Path],
      config: NativeConfig
  ): NativeConfig = {
    val mode = config.mode
    val options = config.options
    val gc = if (config.gc.isEmpty) GC.default.name else config.gc
    val clang = if (config.clang.toString.isEmpty) Discover.clang() else config.clang
    val clangpp = if (config.clangpp.toString.isEmpty) Discover.clangpp() else config.clangpp
    val lopts = if (options.linker.isEmpty) Discover.linkingOptions() else options.linker
    val copts = if (options.compiler.isEmpty) Discover.compileOptions() else options.compiler

    val targetTriple: String = {
      if (config.targetTriple.nonEmpty) config.targetTriple
      else {
        val workdir = project.out.resolve("native").underlying
        Discover.targetTriple(clang, workdir)
      }
    }

    val nativelib: Path = {
      if (config.nativelib.toString.nonEmpty) config.nativelib
      else {
        Discover
          .nativelib(classpath)
          .getOrElse(sys.error("Fatal: nativelib is missing and could not be found."))
      }
    }

    NativeConfig(
      version = config.version,
      mode = mode,
      toolchain = Nil, // No worries, toolchain is on this project's classpath
      nativelib = nativelib,
      gc = gc,
      targetTriple = targetTriple,
      clang = clang,
      clangpp = clangpp,
      options = options,
      linkStubs = config.linkStubs,
      output = config.output
    )
  }
}
