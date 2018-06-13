package bloop.config

import java.nio.file.{Files, Path, Paths}

object Config {
  private final val emptyPath = Paths.get("")
  case class Java(options: Array[String])
  object Java { private[bloop] val empty = Java(Array()) }

  case class TestFramework(names: List[String])
  object TestFramework { private[bloop] val empty = TestFramework(Nil) }
  case class TestArgument(args: Array[String], framework: Option[TestFramework])
  object TestArgument { private[bloop] val empty = TestArgument(Array(), None) }
  case class TestOptions(excludes: List[String], arguments: List[TestArgument])
  object TestOptions { private[bloop] val empty = TestOptions(Nil, Nil) }

  case class Test(frameworks: Array[TestFramework], options: TestOptions)
  object Test { private[bloop] val empty = Test(Array(), TestOptions.empty) }

  case class Sbt(
      sbtVersion: String,
      autoImports: List[String]
  )

  object Sbt {
    private[bloop] val empty = Sbt("", Nil)
  }

  sealed abstract class CompileOrder(val id: String)
  case object Mixed extends CompileOrder("mixed")
  case object JavaThenScala extends CompileOrder("java->scala")
  case object ScalaThenJava extends CompileOrder("scala->java")

  object CompileOrder {
    final val All: List[String] = List(Mixed.id, JavaThenScala.id, ScalaThenJava.id)
  }

  case class CompileSetup(
      order: CompileOrder,
      addLibraryToBootClasspath: Boolean,
      addCompilerToClasspath: Boolean,
      addExtraJarsToClasspath: Boolean,
      manageBootClasspath: Boolean,
      filterLibraryFromClasspath: Boolean
  )

  object CompileSetup {
    private[bloop] val empty: CompileSetup = CompileSetup(Mixed, true, false, false, true, true)
  }

  case class Scala(
      organization: String,
      name: String,
      version: String,
      options: Array[String],
      jars: Array[Path]
  )

  object Scala {
    private[bloop] val empty: Scala = Scala("", "", "", Array(), Array())
  }

  sealed abstract class Platform(val name: String) {
    type Config <: PlatformConfig
    def config: Config
  }

  object Platform {
    private[bloop] val default: Platform = Jvm(JvmConfig.empty)

    object Js { val name: String = "js" }
    case class Js(override val config: JsConfig) extends Platform(Js.name) {
      type Config = JsConfig
    }

    object Jvm { val name: String = "jvm" }
    case class Jvm(override val config: JvmConfig) extends Platform(Jvm.name) {
      type Config = JvmConfig
    }

    object Native { val name: String = "native" }
    case class Native(override val config: NativeConfig) extends Platform(Native.name) {
      type Config = NativeConfig
    }

    final val All: List[String] = List(Jvm.name, Js.name, Native.name)
  }

  sealed trait PlatformConfig

  case class JvmConfig(home: Option[Path], options: List[String]) extends PlatformConfig
  object JvmConfig { private[bloop] val empty = JvmConfig(None, Nil) }

  sealed abstract class LinkerMode(val id: String)
  object LinkerMode {
    case object Debug extends LinkerMode("debug")
    case object Release extends LinkerMode("release")
    val All = List(Debug.id, Release.id)
  }

  case class JsConfig(
      version: String,
      mode: LinkerMode,
      toolchain: List[Path]
  ) extends PlatformConfig

  object JsConfig { private[bloop] val empty: JsConfig = JsConfig("", LinkerMode.Debug, Nil) }

  /**
   * Represents the native platform and all the options it takes.
   *
   * For the description of these fields, see:
   * http://static.javadoc.io/org.scala-native/tools_2.10/0.3.7/index.html#scala.scalanative.build.Config
   *
   * The only field that has been replaced for user-friendliness is `targetTriple` by `platform`.
   */
  case class NativeConfig(
      version: String,
      mode: LinkerMode,
      gc: String,
      targetTriple: String,
      nativelib: Path,
      clang: Path,
      clangpp: Path,
      toolchain: List[Path],
      options: NativeOptions,
      linkStubs: Boolean
  ) extends PlatformConfig

  object NativeConfig {
    // FORMAT: OFF
    private[bloop] val empty: NativeConfig = NativeConfig("", LinkerMode.Debug, "", "", emptyPath, emptyPath, emptyPath, Nil, NativeOptions.empty, false)
    // FORMAT: ON
  }

  case class NativeOptions(linker: List[String], compiler: List[String])
  object NativeOptions {
    private[bloop] val empty: NativeOptions = NativeOptions(Nil, Nil)
  }

  case class Checksum(
      `type`: String,
      digest: String
  )

  object Checksum {
    private[bloop] val empty: Checksum = Checksum("", "")
  }

  case class Artifact(
      name: String,
      classifier: Option[String],
      checksum: Option[Checksum],
      path: Path
  )

  object Artifact {
    private[bloop] val empty: Artifact = Artifact("", None, None, emptyPath)
  }

  case class Module(
      organization: String,
      name: String,
      version: String,
      configurations: Option[String],
      artifacts: List[Artifact]
  )

  object Module {
    private[bloop] val empty: Module = Module("", "", "", None, Nil)
  }

  case class Resolution(
      modules: List[Module]
  )

  object Resolution {
    private[bloop] val empty: Resolution = Resolution(Nil)
  }

  case class Project(
      name: String,
      directory: Path,
      sources: Array[Path],
      dependencies: Array[String],
      classpath: Array[Path],
      out: Path,
      analysisOut: Path,
      classesDir: Path,
      `scala`: Scala,
      java: Java,
      sbt: Sbt,
      test: Test,
      platform: Platform,
      compileSetup: CompileSetup,
      resolution: Resolution
  )

  object Project {
    // FORMAT: OFF
    private[bloop] val empty: Project = Project("", emptyPath, Array(), Array(), Array(), emptyPath, emptyPath, emptyPath, Scala.empty, Java.empty, Sbt.empty, Test.empty, Platform.default, CompileSetup.empty, Resolution.empty)
    // FORMAT: ON

    def analysisFileName(projectName: String) = s"$projectName-analysis.bin"
  }

  case class File(version: String, project: Project)
  object File {
    final val LatestVersion = "1.0.0"

    private[bloop] val empty = File(LatestVersion, Project.empty)

    private[bloop] def dummyForTests: File = {
      val workingDirectory = Paths.get(System.getProperty("user.dir"))
      val sourceFile = Files.createTempFile("Foo", ".scala")
      sourceFile.toFile.deleteOnExit()

      // Just add one classpath with the scala library in it
      val scalaLibraryJar = Files.createTempFile("scala-library", ".jar")
      scalaLibraryJar.toFile.deleteOnExit()

      // This is like `target` in sbt.
      val outDir = Files.createTempFile("out", "test")
      outDir.toFile.deleteOnExit()

      val outAnalysisFile = outDir.resolve("out-analysis.bin")
      outAnalysisFile.toFile.deleteOnExit()

      val classesDir = Files.createTempFile("classes", "test")
      classesDir.toFile.deleteOnExit()

      val platform = Platform.Jvm(JvmConfig(Some(Paths.get("/usr/lib/jvm/java-8-jdk")), Nil))
      val project = Project(
        "dummy-project",
        workingDirectory,
        Array(sourceFile),
        Array("dummy-2"),
        Array(scalaLibraryJar),
        classesDir,
        outDir,
        outAnalysisFile,
        Scala("org.scala-lang", "scala-compiler", "2.12.4", Array("-warn"), Array()),
        Java(Array("-version")),
        Sbt("1.1.0", Nil),
        Test(Array(), TestOptions(Nil, Nil)),
        platform,
        CompileSetup.empty,
        Resolution.empty
      )

      File(LatestVersion, project)
    }
  }
}
