package bloop.integrations.mill

import _root_.mill._
import _root_.mill.define._
import _root_.mill.scalalib._
import _root_.mill.eval.Evaluator
import ammonite.ops._
import bloop.config.util.ConfigUtil

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
    def classes(m: JavaModule) = out(m) / "classes"

    val javaConfig = module.javacOptions.map(opts => Some(Config.Java(options = opts.toList)))
    val scalaConfig = module match {
      case s: ScalaModule =>
        T.task {
          val pluginOptions = s.scalacPluginClasspath().map { pathRef => s"-Xplugin:${pathRef.path}"
          }

          Some(
            Config.Scala(
              organization = "org.scala-lang",
              name = "scala-compiler",
              version = s.scalaVersion(),
              options = (s.scalacOptions() ++ pluginOptions).toList,
              jars = s.scalaCompilerClasspath().map(_.path.toNIO).toList,
              analysis = None,
              setup = None
            )
          )
        }
      case _ => T.task(None)
    }

    val platform = T.task {
      Config.Platform.Jvm(
        Config.JvmConfig(
          home = T.ctx().env.get("JAVA_HOME").map(s => Path(s).toNIO),
          options = module.forkArgs().toList
        ),
        mainClass = module.mainClass()
      )
    }

    val testConfig = module match {
      case m: TestModule =>
        T.task {
          Some(
            Config.Test(
              frameworks = m
                .testFrameworks()
                .map(f => Config.TestFramework(List(f)))
                .toList,
              options = Config.TestOptions(
                excludes = List(),
                arguments = List()
              )
            )
          )
        }
      case _ => T.task(None)
    }

    val ivyDepsClasspath =
      module
        .resolveDeps(T.task { module.compileIvyDeps() ++ module.transitiveIvyDeps() })
        .map(_.map(_.path).toSeq)

    def transitiveClasspath(m: JavaModule): Task[Seq[Path]] = T.task {
      m.moduleDeps.map(classes) ++
        m.unmanagedClasspath().map(_.path) ++
        Task.traverse(m.moduleDeps)(transitiveClasspath)().flatten
    }

    val classpath = T.task(transitiveClasspath(module)() ++ ivyDepsClasspath())
    val resources = T.task(module.resources().map(_.path.toNIO).toList)

    val project = T.task {
      Config.Project(
        name = name(module),
        directory = module.millSourcePath.toNIO,
        sources = module.sources().map(_.path.toNIO).toList,
        dependencies = module.moduleDeps.map(name).toList,
        classpath = classpath().map(_.toNIO).toList,
        out = out(module).toNIO,
        classesDir = classes(module).toNIO,
        resources = Some(resources()),
        `scala` = scalaConfig(),
        java = javaConfig(),
        sbt = None,
        test = testConfig(),
        platform = Some(platform()),
        resolution = None
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
