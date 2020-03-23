package bloop.config

import java.nio.file.{Files, Path, Paths}

object Config {
  private final val emptyPath = Paths.get("")
  case class Java(options: List[String])

  case class TestFramework(names: List[String])

  object TestFramework {
    val utest = Config.TestFramework(
      List("utest.runner.Framework")
    )

    val munit = Config.TestFramework(
      List("munit.Framework")
    )

    val ScalaCheck = Config.TestFramework(
      List(
        "org.scalacheck.ScalaCheckFramework"
      )
    )

    val ScalaTest = Config.TestFramework(
      List(
        "org.scalatest.tools.Framework",
        "org.scalatest.tools.ScalaTestFramework"
      )
    )

    val Specs2 = Config.TestFramework(
      List(
        "org.specs.runner.SpecsFramework",
        "org.specs2.runner.Specs2Framework",
        "org.specs2.runner.SpecsFramework"
      )
    )

    val JUnit = Config.TestFramework(
      List(
        "com.novocode.junit.JUnitFramework"
      )
    )

    val DefaultFrameworks = List(JUnit, ScalaTest, ScalaCheck, Specs2, utest, munit)
  }

  case class TestArgument(args: List[String], framework: Option[TestFramework])
  case class TestOptions(excludes: List[String], arguments: List[TestArgument])
  object TestOptions { val empty = TestOptions(Nil, Nil) }

  case class Test(frameworks: List[TestFramework], options: TestOptions)
  object Test {
    def defaultConfiguration: Test = {
      val junit = List(Config.TestArgument(List("-v", "-a"), Some(Config.TestFramework.JUnit)))
      Config.Test(Config.TestFramework.DefaultFrameworks, Config.TestOptions(Nil, junit))
    }
  }

  case class Sbt(
      sbtVersion: String,
      autoImports: List[String]
  )

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
    val empty: CompileSetup = CompileSetup(Mixed, true, false, false, true, true)
  }

  case class Scala(
      organization: String,
      name: String,
      version: String,
      options: List[String],
      jars: List[Path],
      analysis: Option[Path],
      setup: Option[CompileSetup]
  )

  sealed abstract class Platform(val name: String) {
    type Config <: PlatformConfig
    def config: Config
    def mainClass: Option[String]
  }

  object Platform {
    val default: Platform = Jvm(JvmConfig.empty, None)

    object Js { val name: String = "js" }
    case class Js(override val config: JsConfig, override val mainClass: Option[String])
        extends Platform(Js.name) {
      type Config = JsConfig
    }

    object Jvm { val name: String = "jvm" }
    case class Jvm(override val config: JvmConfig, override val mainClass: Option[String])
        extends Platform(Jvm.name) {
      type Config = JvmConfig
    }

    object Native { val name: String = "native" }
    case class Native(override val config: NativeConfig, override val mainClass: Option[String])
        extends Platform(Native.name) {
      type Config = NativeConfig
    }

    final val All: List[String] = List(Jvm.name, Js.name, Native.name)
  }

  sealed trait PlatformConfig
  case class JvmConfig(home: Option[Path], options: List[String]) extends PlatformConfig
  object JvmConfig { val empty = JvmConfig(None, Nil) }

  sealed abstract class LinkerMode(val id: String)
  object LinkerMode {
    case object Debug extends LinkerMode("debug")
    case object Release extends LinkerMode("release")
    val All = List(Debug.id, Release.id)
  }

  sealed abstract class ModuleKindJS(val id: String)
  object ModuleKindJS {
    case object NoModule extends ModuleKindJS("none")
    case object CommonJSModule extends ModuleKindJS("commonjs")
    val All = List(NoModule.id, CommonJSModule.id)
  }

  case class JsConfig(
      version: String,
      mode: LinkerMode,
      kind: ModuleKindJS,
      emitSourceMaps: Boolean,
      jsdom: Option[Boolean],
      output: Option[Path],
      nodePath: Option[Path],
      toolchain: List[Path]
  ) extends PlatformConfig

  object JsConfig {
    val empty: JsConfig =
      JsConfig("", LinkerMode.Debug, ModuleKindJS.NoModule, false, None, None, None, Nil)
  }

  /*
   * Represents the native platform and all the options it takes.
   *
   * For the description of these fields, see:
   * https://static.javadoc.io/org.scala-native/tools_2.12/0.3.9/scala/scalanative/build/Config.html
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
      linkStubs: Boolean,
      output: Option[Path]
  ) extends PlatformConfig

  object NativeConfig {
    // FORMAT: OFF
    val empty: NativeConfig = NativeConfig("", LinkerMode.Debug, "", "", emptyPath, emptyPath, emptyPath, Nil, NativeOptions.empty, false, None)
    // FORMAT: ON
  }

  case class NativeOptions(linker: List[String], compiler: List[String])
  object NativeOptions {
    val empty: NativeOptions = NativeOptions(Nil, Nil)
  }

  case class Checksum(
      `type`: String,
      digest: String
  )

  case class Artifact(
      name: String,
      classifier: Option[String],
      checksum: Option[Checksum],
      path: Path
  )

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

  case class SourcesGlobs(
      directory: Path,
      walkDepth: Option[Int],
      includes: List[String],
      excludes: List[String]
  )

  case class Project(
      name: String,
      directory: Path,
      workspaceDir: Option[Path],
      sources: List[Path],
      sourcesGlobs: Option[List[SourcesGlobs]],
      sourceRoots: Option[List[Path]],
      dependencies: List[String],
      classpath: List[Path],
      out: Path,
      classesDir: Path,
      resources: Option[List[Path]],
      `scala`: Option[Scala],
      java: Option[Java],
      sbt: Option[Sbt],
      test: Option[Test],
      platform: Option[Platform],
      resolution: Option[Resolution],
      tags: Option[List[String]]
  )

  object Project {
    // FORMAT: OFF
    private[bloop] val empty: Project = Project("", emptyPath, None, List(), None, None, List(), List(),  emptyPath, emptyPath, None, None, None, None, None, None, None, None)
    // FORMAT: ON

    def analysisFileName(projectName: String) = s"$projectName-analysis.bin"
  }

  case class File(version: String, project: Project)
  object File {
    // We cannot have the version coming from the build tool
    final val LatestVersion = "1.4.0"
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

      val platform = {
        val jdkPath = Paths.get("/usr/lib/jvm/java-8-jdk")
        Platform.Jvm(JvmConfig(Some(jdkPath), Nil), Some("module.Main"))
      }

      val project = Project(
        "dummy-project",
        workingDirectory,
        Some(workingDirectory),
        List(sourceFile),
        None,
        None,
        List("dummy-2"),
        List(scalaLibraryJar),
        outDir,
        classesDir,
        Some(List(outDir.resolve("resource1.xml"))),
        Some(
          Scala(
            "org.scala-lang",
            "scala-compiler",
            "2.12.4",
            List("-warn"),
            List(),
            Some(outAnalysisFile),
            Some(CompileSetup.empty)
          )
        ),
        Some(Java(List("-version"))),
        Some(Sbt("1.1.0", Nil)),
        Some(Test(List(), TestOptions(Nil, Nil))),
        Some(platform),
        Some(Resolution(Nil)),
        None
      )

      File(LatestVersion, project)
    }
  }
}
