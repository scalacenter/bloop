package bloop

import java.io.FileOutputStream
import java.util.Properties

import sbt._
import Keys._
import sbt.plugins.JvmPlugin

object SbtBloop extends AutoPlugin {
  override def trigger  = allRequirements
  override def requires = JvmPlugin

  object autoImport {
    lazy val bloopConfigDir: SettingKey[File] =
      settingKey[File]("Directory where to write bloop configuration files")
    lazy val bloopInstall: TaskKey[Unit] =
      taskKey[Unit]("Generate bloop configuration files for this project")
    lazy val install: TaskKey[Unit] =
      taskKey[Unit]("Generate all bloop configuration files")
  }

  import autoImport._

  override def globalSettings: Seq[Def.Setting[_]] = Seq(
    bloopConfigDir in Global := (baseDirectory in ThisBuild).value / ".bloop-config",
    install := Def.taskDyn {
      val filter = ScopeFilter(inAnyProject, inConfigurations(Compile, Test))
      bloopInstall.all(filter).map(_ => ())
    }.value
  )

  override def projectSettings: Seq[Def.Setting[_]] =
    List(Compile, Test).flatMap { c =>
      inConfig(c)(
        Seq(bloopInstall := {
          def makeName(name: String, configuration: Configuration): String =
            if (configuration == Compile) name else name + "-test"
          val projectName = makeName(projectID.value.name, configuration.value)
          // TODO: We should extract the right configuration for the dependency.
          val dependencies =
            projectDependencies.value.map(proj => makeName(proj.name, configuration.value))
          val scalaOrganization =
            Keys.ivyScala.value
              .map(_.scalaOrganization)
              .getOrElse("org.scala-lang")
          val scalaName  = "scala-compiler"
          val classpath  = dependencyClasspath.value.map(_.data.getAbsoluteFile)
          val classesDir = classDirectory.value.getAbsoluteFile
          val sourceDirs = sourceDirectories.value
          val tmp        = target.value / "tmp-bloop"
          val outFile    = bloopConfigDir.value / (projectName + ".config")
          val config =
            Config(
              projectName,
              dependencies,
              scalaOrganization,
              scalaName,
              scalaVersion.value,
              classpath,
              classesDir,
              scalacOptions.value,
              javacOptions.value,
              sourceDirs,
              tmp
            )
          val properties = config.toProperties
          IO.createDirectory(bloopConfigDir.value)
          val stream = new FileOutputStream(outFile)
          try properties.store(stream, null)
          finally stream.close()

          streams.value.log.info(s"Wrote configuration of project '$projectName' to '$outFile'")
        })
      )
    }

  private case class Config(name: String,
                            dependencies: Seq[String],
                            scalaOrganization: String,
                            scalaName: String,
                            scalaVersion: String,
                            classpath: Seq[File],
                            classesDir: File,
                            scalacOptions: Seq[String],
                            javacOptions: Seq[String],
                            sourceDirectories: Seq[File],
                            tmp: File) {
    def toProperties: Properties = {
      val properties = new Properties()
      properties.setProperty("name", name)
      properties.setProperty("dependencies", dependencies.mkString(","))
      properties.setProperty("scalaOrganization", scalaOrganization)
      properties.setProperty("scalaName", scalaName)
      properties.setProperty("scalaVersion", scalaVersion)
      properties.setProperty("classpath", classpath.map(_.getAbsolutePath).mkString(","))
      properties.setProperty("classesDir", classesDir.getAbsolutePath)
      properties.setProperty("scalacOptions", scalacOptions.mkString(","))
      properties.setProperty("javacOptions", javacOptions.mkString(","))
      properties.setProperty("sourceDirectories",
                             sourceDirectories.map(_.getAbsolutePath).mkString(","))
      properties.setProperty("tmp", tmp.getAbsolutePath)
      properties
    }

  }
}
