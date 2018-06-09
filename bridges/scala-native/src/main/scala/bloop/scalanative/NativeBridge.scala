package bloop.scalanative

import bloop.Project
import bloop.cli.OptimizerConfig
import bloop.config.Config.{NativeConfig, NativeOptions}
import bloop.config.Config.Platform
import bloop.io.Paths
import bloop.logging.Logger
import java.nio.file.{Files, Path}

import scala.scalanative.build.{Build, Config, Discover, GC, Mode, Logger => NativeLogger}

object NativeBridge {

  def nativeLink(project: Project,
                 entry: String,
                 logger: Logger,
                 optimize: OptimizerConfig): Path = {
    val classpath = project.classpath.map(_.underlying)
    val workdir = project.out.resolve("native")

    Paths.delete(workdir)
    Files.createDirectories(workdir.underlying)

    val outpath = workdir.resolve("out")
    val nativeLogger = NativeLogger(logger.debug _, logger.info _, logger.warn _, logger.error _)
    val nativeConfig = project.platform match {
      case Platform.Native(config) => config
      case _ => defaultNativeConfig(project)
    }

    val nativeMode = optimize match {
      case OptimizerConfig.Debug => Mode.debug
      case OptimizerConfig.Release => Mode.release
    }

    val config =
      Config.empty
        .withGC(GC(nativeConfig.gc))
        .withMode(nativeMode)
        .withClang(nativeConfig.clang)
        .withClangPP(nativeConfig.clangpp)
        .withLinkingOptions(nativeConfig.options.linker.toSeq.toArray)
        .withCompileOptions(nativeConfig.options.compiler.toSeq.toArray)
        .withTargetTriple(nativeConfig.platform)
        .withNativelib(nativeConfig.nativelib)
        .withLinkStubs(nativeConfig.linkStubs)
        .withMainClass(entry)
        .withClassPath(classpath)
        .withWorkdir(workdir.underlying)
        .withLogger(nativeLogger)

    Build.build(config, outpath.underlying)
  }

  private[scalanative] def defaultNativeConfig(project: Project): NativeConfig = {
    val classpath = project.classpath.map(_.underlying)
    val workdir = project.out.resolve("native").underlying

    val clang = Discover.clang()
    val options = NativeOptions(Discover.linkingOptions().toList, Discover.compileOptions().toList)

    NativeConfig(
      toolchainClasspath = Nil, // Toolchain is on the classpath of this project, so that's fine
      nativelib = Discover.nativelib(classpath).get,
      gc = GC.default.name,
      platform = Discover.targetTriple(clang, workdir),
      clang = clang,
      clangpp = Discover.clangpp(),
      options = options,
      linkStubs = true
    )
  }
}
