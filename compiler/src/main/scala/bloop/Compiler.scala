package bloop

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
  Inputs
}
import sbt.internal.inc._
import java.util.Optional
import java.io.File
import java.nio.file.{Path, Paths}

object Compiler {

  final val ZINC_VERSION = "1.0.2"
  val logger             = QuietLogger

  def compile(project: Project, compilerCache: CompilerCache): CompileResult = {
    val scalaOrganization = project.scalaInstance.organization
    val scalaName         = project.scalaInstance.name
    val scalaVersion      = project.scalaInstance.version
    val compiler          = compilerCache.get((scalaOrganization, scalaName, scalaVersion))
    compile(project, compiler)
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
          /* secondaryCacheDir    = */ Some(Paths.get(s"$home/.bloop/secondary-cache").toFile),
          /* dependencyResolution = */ DependencyResolution.getEngine,
          /* compilerBridgeSource = */ ZincUtil.getDefaultBridgeModule(scalaInstance.version),
          /* scalaJarsTarget      = */ scalaJarsTarget.toFile,
          /* log                  = */ logger
        )
    }
  }

  def getCompilationOptions(inputs: Project): CompileOptions = {
    val sources = inputs.sourceDirectories.distinct
      .flatMap(src => IO.getAll(src, "glob:**.{scala,java}"))
      .distinct

    CompileOptions
      .create()
      .withClassesDirectory(inputs.classesDir.toFile)
      .withSources(sources.map(_.toFile))
      .withClasspath(inputs.classpath.map(_.toFile) ++ inputs.scalaInstance.allJars ++ Array(
        inputs.classesDir.toFile))
  }

  def getSetup(inputs: Project): Setup = {
    val perClasspathEntryLookup =
      new PerClasspathEntryLookup {
        override def analysis(classpathEntry: File): Optional[CompileAnalysis] = {
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
      /*                  reporter  = */ new LoggedReporter(100, logger),
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

  private def compile(project: Project, compilers: Compilers): CompileResult = {
    val zincInputs = Inputs.of(compilers,
                               getCompilationOptions(project),
                               getSetup(project),
                               project.previousResult)
    val incrementalCompiler = ZincUtil.defaultIncrementalCompiler
    incrementalCompiler.compile(zincInputs, logger)
  }
}
