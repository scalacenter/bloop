package bloop.integrations.mill

import _root_.mill._
import _root_.mill.define._
import _root_.mill.scalalib._
import _root_.mill.eval.Evaluator

import ammonite.ops._

object Bloop extends ExternalModule {

  def install(ev: Evaluator[Any]) = T.command {
    val bloopDir = pwd / ".bloop"
    mkdir(bloopDir)

    val rootModule = ev.rootModule
    val modules = rootModule.millInternal.segmentsToModules.values.collect {
      case m: scalalib.JavaModule => m
    }.toSeq

    Task.traverse(modules)(genBloopConfig(bloopDir, _))
  }

  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()

  def genBloopConfig(bloopDir: Path, module: JavaModule): Task[Path] = {

    import bloop.config.Config

    def name(m: JavaModule) = m.millModuleSegments.render
    def out(m: JavaModule) = bloopDir / "out" / m.millModuleSegments.render
    def analysisOut(m: JavaModule) = out(m) / Config.Project.analysisFileName(name(m))
    def classes(m: JavaModule) = out(m) / "classes"

    val javaConfig = module.javacOptions.map(opts => Config.Java(options = opts.toArray))

    val scalaConfig = module match {
      case s: ScalaModule =>
        T.task {
          val pluginOptions = s.scalacPluginClasspath().map { pathRef =>
            s"-Xplugin:${pathRef.path}"
          }

          Config.Scala(
            organization = "org.scala-lang",
            name = "scala-compiler",
            version = s.scalaVersion(),
            options = (s.scalacOptions() ++ pluginOptions).toArray,
            jars = s.scalaCompilerClasspath().map(_.path.toNIO).toArray
          )
        }
      case _ => T.task(Config.Scala.empty)
    }

    val platform = T.task {
      Config.Platform.Jvm(
        Config.JvmConfig(
          home = T.ctx().env.get("JAVA_HOME").map(s => Path(s).toNIO),
          options = module.forkArgs().toList
        )
      )
    }

    val testConfig = module match {
      case m: TestModule =>
        T.task {
          Config.Test(
            frameworks = m
              .testFrameworks()
              .map(f => Config.TestFramework(List(f)))
              .toArray,
            options = Config.TestOptions(
              excludes = List(),
              arguments = List()
            )
          )
        }
      case _ => T.task(Config.Test.empty)
    }

    val ivyDepsClasspath =
      module
        .resolveDeps(T.task { module.compileIvyDeps() ++ module.transitiveIvyDeps() })
        .map(_.map(_.path).toSeq)

    def transitiveClasspath(m: JavaModule): Task[Seq[Path]] = T.task {
      m.moduleDeps.map(classes) ++
        m.resources().map(_.path) ++
        m.unmanagedClasspath().map(_.path) ++
        Task.traverse(m.moduleDeps)(transitiveClasspath)().flatten
    }

    val classpath = T.task(transitiveClasspath(module)() ++ ivyDepsClasspath())
    val compileSetup = Config.CompileSetup(
      Config.Mixed,
      addLibraryToBootClasspath = true,
      addCompilerToClasspath = false,
      addExtraJarsToClasspath = false,
      manageBootClasspath = true,
      filterLibraryFromClasspath = true
    )

    val project = T.task {
      Config.Project(
        name = name(module),
        directory = module.millSourcePath.toNIO,
        sources = module.sources().map(_.path.toNIO).toArray,
        dependencies = module.moduleDeps.map(name).toArray,
        classpath = classpath().map(_.toNIO).toArray,
        out = out(module).toNIO,
        analysisOut = analysisOut(module).toNIO,
        classesDir = classes(module).toNIO,
        `scala` = scalaConfig(),
        java = javaConfig(),
        test = testConfig(),
        platform = platform(),
        compileSetup = compileSetup(),
        resolution = Config.Resolution.empty
      )
    }

    T.task {
      val filePath = bloopDir / s"${name(module)}.json"
      val file = Config.File(
        version = Config.File.LatestVersion,
        project = project()
      )

      bloop.config.write(file, filePath.toNIO)
      filePath
    }
  }

  lazy val millDiscover = mill.define.Discover[this.type]

}
