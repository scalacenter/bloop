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
  }

  /**
   * Extension methods for java.io.File
   */
  implicit class FileExtension(file: File) {
    def /(child: String): File = new File(file, child)
  }
}
