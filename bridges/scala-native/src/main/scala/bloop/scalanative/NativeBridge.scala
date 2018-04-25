package bloop.scalanative

import bloop.Project
import bloop.io.AbsolutePath
import bloop.logging.Logger

import java.nio.file.Path

import scala.scalanative.build.{Discover, Build, Config, GC, Mode, Logger => NativeLogger}

object NativeBridge {

  def nativeLink(project: Project, entry: String, logger: Logger): Path = {
    val classpath = project.classpath.map(_.underlying)
    val workdir = project.out.resolve("native").underlying

    val clang = Discover.clang()
    val clangpp = Discover.clangpp()
    val linkopts = Discover.linkingOptions()
    val compopts = Discover.compileOptions()
    val triple = Discover.targetTriple(clang, workdir)
    val nativelib = Discover.nativelib(classpath).get
    val outpath = workdir.resolve("out")
    val nativeLogger = NativeLogger(logger.debug _, logger.info _, logger.warn _, logger.error _)

    val config =
      Config.empty
        .withGC(GC.default)
        .withMode(Mode.default)
        .withClang(clang)
        .withClangPP(clangpp)
        .withLinkingOptions(linkopts)
        .withCompileOptions(compopts)
        .withTargetTriple(triple)
        .withNativelib(nativelib)
        .withMainClass(entry)
        .withClassPath(classpath)
        .withWorkdir(workdir)
        .withLogger(nativeLogger)

    Build.build(config, outpath)
  }

}
