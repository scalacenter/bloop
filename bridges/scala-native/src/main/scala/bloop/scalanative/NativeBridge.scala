package bloop.scalanative

import java.io.File

import bloop.Project
import bloop.logging.Logger
import java.nio.file.Path

import scala.scalanative.build.{Build, Config, Discover, GC, Mode, Logger => NativeLogger}

object NativeBridge {

  def link(project: Project,
           entry  : String,
           target : File,
           logger : Logger): Path = {
    val classpath = project.classpath.map(_.underlying)
    val workdir = project.out.resolve("native").underlying

    val clang = Discover.clang()
    val clangpp = Discover.clangpp()
    val linkopts = Discover.linkingOptions()
    val compopts = Discover.compileOptions()
    val triple = Discover.targetTriple(clang, workdir)
    val nativelib = Discover.nativelib(classpath).get
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

    Build.build(config, target.toPath)
  }

}
