package bloop

import java.io.FileOutputStream
import java.io.File

import sbt.{Keys, AutoPlugin, Def, Global, ScopeFilter, ThisBuild, Compile, Test, Configuration}

object SbtBloop extends AutoPlugin {
  import sbt.plugins.JvmPlugin
  override def requires = JvmPlugin
  override def trigger = allRequirements
  final val autoImport = AutoImportedKeys

  override def globalSettings: Seq[Def.Setting[_]] = PluginImplementation.globalSettings
  override def projectSettings: Seq[Def.Setting[_]] = PluginImplementation.projectSettings
}

object AutoImportedKeys {
  import sbt.{TaskKey, taskKey, settingKey, SettingKey}
  private[bloop] val bloopInstall: TaskKey[Unit] =
    taskKey[Unit]("Generate bloop configuration files for this project")
  val bloopConfigDir: SettingKey[File] =
    settingKey[File]("Directory where to write bloop configuration files")
  val install: TaskKey[Unit] =
    taskKey[Unit]("Generate all bloop configuration files")
}

object PluginImplementation {

  def globalSettings: Seq[Def.Setting[_]] = List(
    AutoImportedKeys.install := PluginDefaults.install.value,
    AutoImportedKeys.bloopConfigDir in Global := PluginDefaults.bloopConfigDir.value
  )

  def projectSettings: Seq[Def.Setting[_]] =
    List(Compile, Test).flatMap(conf =>
      sbt.inConfig(conf)(AutoImportedKeys.bloopInstall := PluginDefaults.bloopInstall.value))

  case class Config(
      name: String,
      dependencies: Seq[String],
      scalaOrganization: String,
      scalaName: String,
      scalaVersion: String,
      classpath: Seq[File],
      classesDir: File,
      scalacOptions: Seq[String],
      javacOptions: Seq[String],
      sourceDirectories: Seq[File],
      allScalaJars: Seq[File],
      tmp: File
  ) {
    private def seqToString[T](xs: Seq[T], sep: String = ","): String = xs.mkString(sep)
    private def toPaths(xs: Seq[File]): Seq[String] = xs.map(_.getAbsolutePath)
    def toProperties: java.util.Properties = {
      val properties = new java.util.Properties()
      properties.setProperty("name", name)
      properties.setProperty("dependencies", seqToString(dependencies))
      properties.setProperty("scalaOrganization", scalaOrganization)
      properties.setProperty("scalaName", scalaName)
      properties.setProperty("scalaVersion", scalaVersion)
      properties.setProperty("classpath", seqToString(toPaths(classpath)))
      properties.setProperty("classesDir", classesDir.getAbsolutePath)
      properties.setProperty("scalacOptions", seqToString(scalacOptions, ";"))
      properties.setProperty("javacOptions", seqToString(javacOptions, ";"))
      properties.setProperty("sourceDirectories", seqToString(toPaths(sourceDirectories)))
      properties.setProperty("allScalaJars", seqToString(toPaths(allScalaJars)))
      properties.setProperty("tmp", tmp.getAbsolutePath)
      properties
    }
  }

  object PluginDefaults {
    import sbt.Task
    import bloop.Compat._

    final val bloopInstall: Def.Initialize[Task[Unit]] = Def.task {
      def makeName(name: String, configuration: Configuration): String =
        if (configuration == Compile) name else name + "-test"

      val logger = Keys.streams.value.log
      val project = Keys.thisProject.value
      val configuration = Keys.configuration.value
      val projectName = makeName(project.id, configuration)

      // In the test configuration, add a dependency on the base project
      val baseProjectDependency = if (configuration == Test) List(project.id) else Nil

      // TODO: We should extract the right configuration for the dependency.
      val projectDependencies =
        project.dependencies.map(dep => makeName(dep.project.project, configuration))
      val dependencies = projectDependencies ++ baseProjectDependency
      // TODO: We should extract the right configuration for the aggregate.
      val aggregates = project.aggregate.map(agg => makeName(agg.project, configuration))
      val dependenciesAndAggregates = dependencies ++ aggregates

      val scalaName = "scala-compiler"
      val scalaVersion = Keys.scalaVersion.value
      val scalaOrg = Keys.ivyScala.value.map(_.scalaOrganization).getOrElse("org.scala-lang")
      val allScalaJars = Keys.scalaInstance.value.allJars.map(_.getAbsoluteFile)
      val classpath = PluginDefaults.emulateDependencyClasspath.value.map(_.getAbsoluteFile)
      val classesDir = Keys.classDirectory.value.getAbsoluteFile
      val sourceDirs = Keys.sourceDirectories.value
      val scalacOptions = Keys.scalacOptions.value
      val javacOptions = Keys.javacOptions.value
      val tmp = Keys.target.value / "tmp-bloop"
      val bloopConfigDir = AutoImportedKeys.bloopConfigDir.value
      val outFile = bloopConfigDir / s"$projectName.config"

      // format: OFF
      val config = Config(projectName, dependenciesAndAggregates, scalaOrg, scalaName,scalaVersion,
        classpath, classesDir, scalacOptions, javacOptions, sourceDirs, allScalaJars, tmp)
      sbt.IO.createDirectory(bloopConfigDir)
      val stream = new FileOutputStream(outFile)
      try config.toProperties.store(stream, null)
      finally stream.close()
      logger.success(s"Bloop wrote the configuration of project '$projectName' to '$outFile'.")
      // format: ON
    }

    final val install: Def.Initialize[Task[Unit]] = Def.taskDyn {
      val filter = ScopeFilter(sbt.inAnyProject, sbt.inConfigurations(Compile, Test))
      AutoImportedKeys.bloopInstall.all(filter).map(_ => ())
    }

    final val bloopConfigDir: Def.Initialize[File] = Def.setting {
      (Keys.baseDirectory in ThisBuild).value / ".bloop-config"
    }

    import sbt.Classpaths
    final val emulateDependencyClasspath: Def.Initialize[Task[Seq[File]]] = Def.taskDyn {
      val projectRef = Keys.thisProjectRef.value
      val data = Keys.settingsData.value
      val deps = Keys.buildDependencies.value

      import scala.collection.JavaConverters._
      import sbt.Configurations.CompilerPlugin
      val confName = CompilerPlugin.name
      val visited = Classpaths.interSort(projectRef, CompilerPlugin, data, deps)
      val tasks = (new java.util.LinkedHashSet[Task[Def.Classpath]]).asScala
      for ((dep, c) <- visited)
        if ((dep != projectRef) || (confName != c))
          tasks += Classpaths.getClasspath(Keys.exportedProductsNoTracking, dep, c, data)
      val internalClasspathTask = (tasks.toSeq.join).map(_.flatten.distinct)
      Def.task {
        val internalClasspath = internalClasspathTask.value
        val externalClasspath = Keys.externalDependencyClasspath.value
        (internalClasspath ++ externalClasspath).map(_.data)
      }
    }
  }
}
