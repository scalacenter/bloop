package bloop.integrations.gradle

import java.io.File

import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.SourceSet
import org.gradle.api.{Project, Task, GradleException}

import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import java.nio.file.Path

object syntax {

  /**
   * Extension methods for Gradle's Project model
   */
  implicit class ProjectExtension(project: Project) {
    def createTask[T <: Task](name: String)(implicit t: ClassTag[T]): T = {
      project.getTasks.create(name, t.runtimeClass.asInstanceOf[Class[T]])
    }

    def getTask[T <: Task](name: String): T = {
      project.getTasks.findByName(name).asInstanceOf[T]
    }

    def getConfiguration(name: String): Configuration = {
      val configs = project.getConfigurations
      configs.findByName(name)
    }

    def allSourceSets: Set[SourceSet] = {
      Option(project.getExtensions.findByType(classOf[SourceSetContainer]))
        .map(_.asScala.toSet)
        .getOrElse(Set.empty)
    }

    def javaApplicationExt: Option[JavaApplication] = {
      Option(project.getExtensions.findByType(classOf[JavaApplication]))
    }

    def createExtension[T](name: String, params: Object*)(implicit t: ClassTag[T]): Unit = {
      project.getExtensions.create(name, t.runtimeClass.asInstanceOf[Class[T]], params: _*)
      ()
    }

    def getExtension[T](implicit t: ClassTag[T]): T = {
      project.getExtensions.getByType(t.runtimeClass.asInstanceOf[Class[T]])
    }

    private def getCommonRootPath(path1: Path, path2: Path): Path = {
      var idx = 0
      var finished = false
      while (!finished) {
        if (
          idx < path1.getNameCount() && idx < path2.getNameCount() && path1.getName(idx) == path2
            .getName(idx)
        )
          idx = idx + 1
        else
          finished = true
      }
      path1.getRoot().resolve(path1.subpath(0, idx))
    }

    def workspacePath: Path = {
      project.getAllprojects.asScala
        .map(_.getProjectDir().toPath())
        .foldLeft(project.getRootDir().toPath())(getCommonRootPath)
    }
  }

  /**
   * Extension methods for java.io.File
   */
  implicit class FileExtension(file: File) {
    def /(child: String): File = new File(file, child)
  }
}
