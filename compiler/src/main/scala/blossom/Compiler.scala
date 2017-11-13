package blossom

import xsbti.compile.{
  ClasspathOptions,
  CompileAnalysis,
  CompileOptions,
  CompileProgress,
  CompileResult,
  Compilers,
  DefinesClass,
  IncOptions,
  PerClasspathEntryLookup,
  Setup,
  Inputs => ZincInputs
}
import sbt.internal.inc._
import java.util.Optional
import java.io.File
import java.nio.file.{Path, Paths}

object Compiler {

  final val ZINC_VERSION = "1.0.2"

  def apply(inputs: Project, compilers: Compilers): CompileResult = {
    val zincInputs = ZincInputs.of(compilers,
                                   getCompilationOptions(inputs),
                                   getSetup(inputs),
                                   inputs.previousResult)
    apply(zincInputs)
  }

  private def apply(inputs: ZincInputs): CompileResult = {
    val incrementalCompiler = ZincUtil.defaultIncrementalCompiler
    incrementalCompiler.compile(inputs, ConsoleLogger)
  }

  private val home = System.getProperty("user.home")
  def getScalaCompiler(scalaInstance: ScalaInstance,
                       classpathOptions: ClasspathOptions,
                       componentProvider: ComponentProvider,
                       scalaJarsTarget: Path): AnalyzingCompiler = {
    componentProvider.component(bridgeComponentID(scalaInstance.version)) match {
      case Array(jar) =>
        ZincUtil.scalaCompiler(
                               /* scalaInstance     = */ scalaInstance,
                               /* compilerBridgeJar = */ jar,
                               /* classpathOptions  = */ classpathOptions)
      case _ =>
        ZincUtil.scalaCompiler(
          /* scalaInstance        = */ scalaInstance,
          /* classpathOptions     = */ classpathOptions,
          /* globalLock           = */ GlobalLock,
          /* componentProvider    = */ componentProvider,
          /* secondaryCacheDir    = */ Some(
            Paths.get(s"$home/.blossom/secondary-cache").toFile),
          /* dependencyResolution = */ DependencyResolution.getEngine(null),
          /* compilerBridgeSource = */ ZincUtil.getDefaultBridgeModule(
            scalaInstance.version),
          /* scalaJarsTarget      = */ scalaJarsTarget.toFile,
          /* log                  = */ ConsoleLogger
        )
    }
  }

  def getCompilationOptions(inputs: Project): CompileOptions = {
    val sources = inputs.sourceDirectories.flatMap(src =>
      IO.getAll(src, "glob:**.{scala,java}"))
    CompileOptions
      .create()
      .withClassesDirectory(inputs.classesDir.toFile)
      .withSources(sources.map(_.toFile))
      .withClasspath(
        inputs.classpath.map(_.toFile) ++ inputs.scalaInstance.allJars)
  }

  def getSetup(inputs: Project): Setup = {
    val perClasspathEntryLookup =
      new PerClasspathEntryLookup {
        override def analysis(
            classpathEntry: File): Optional[CompileAnalysis] = {
          inputs.previousResult.analysis
        }

        override def definesClass(classpathEntry: File): DefinesClass =
          Locate.definesClass(classpathEntry)
      }
    Setup.create(
      /* perClasspathEntryLookup    = */ perClasspathEntryLookup,
      /*                    skip    = */ false,
      /*               cacheFile    = */ inputs.tmp.resolve("cache").toFile,
      /*                   cache    = */ new FreshCompilerCache,
      /* incrementalCompilerOptions = */ IncOptions.create(),
      /*                  reporter  = */ new LoggedReporter(100, ConsoleLogger),
      /*                  progress  = */ Optional.empty[CompileProgress],
      /*                     extra  = */ Array.empty
    )
  }

  def bridgeComponentID(scalaVersion: String): String = {
    val shortScalaVersion = scalaVersion.take(4)
    val classVersion      = sys.props("java.class.version")
    s"org.scala-sbt-compiler-bridge_${shortScalaVersion}-${ZINC_VERSION}-bin_${scalaVersion}__${classVersion}"
  }

  def bridgeComponentID(inputs: Project): String =
    bridgeComponentID(inputs.scalaInstance.version)
}
