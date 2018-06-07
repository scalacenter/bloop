package bloop.scalanative

import bloop.Project
import bloop.config.Config.NativeConfig
import bloop.io.{AbsolutePath, Paths}
import bloop.logging.Logger

import java.nio.file.{Files, Path}

import scala.scalanative.build.{Discover, Build, Config, GC, Mode, Logger => NativeLogger}

object NativeBridge {

  def nativeLink(project: Project, entry: String, logger: Logger): Path = {
    val classpath = project.classpath.map(_.underlying)
    val workdir = project.out.resolve("native")

    Paths.delete(workdir)
    Files.createDirectories(workdir.underlying)

    val outpath = workdir.resolve("out")
    val nativeLogger = NativeLogger(logger.debug _, logger.info _, logger.warn _, logger.error _)
    val nativeConfig = project.nativeConfig.getOrElse(defaultNativeConfig(project))

    val config =
      Config.empty
        .withGC(GC(nativeConfig.gc))
        .withMode(Mode.default)
        .withClang(nativeConfig.clang)
        .withClangPP(nativeConfig.clangPP)
        .withLinkingOptions(nativeConfig.linkingOptions)
        .withCompileOptions(nativeConfig.compileOptions)
        .withTargetTriple(nativeConfig.targetTriple)
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

    NativeConfig(
      toolchainClasspath = Array.empty, // Toolchain is on the classpath of this project, so that's fine
      gc = GC.default.name,
      clang = clang,
      clangPP = Discover.clangpp(),
      linkingOptions = Discover.linkingOptions().toArray,
      compileOptions = Discover.compileOptions().toArray,
      targetTriple = Discover.targetTriple(clang, workdir),
      nativelib = Discover.nativelib(classpath).get,
      linkStubs = true
    )
  }

}
