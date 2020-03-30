package bloop.integrations.gradle

import java.io.File

import org.gradle.api.provider.Property
import org.gradle.api.Project
import org.gradle.api.tasks.{Input, Optional}
import syntax._

/**
 * Project extension to configure Bloop.
 *
 * From the build definitions it can be used like the following:
 *
 * {{{
 * bloop {
 *   targetDir = file("$projectDir/.bloop")
 *   compilerName = "scala-compiler"
 *   stdLibName = "scala-library"
 *   includeSources = true
 *   includeJavaDoc = false
 *   dottyVersion = "latest"
 * }
 * }}}
 */
case class BloopParametersExtension(project: Project) {
  // Variable names are derived by Gradle from the annotated getter method

  // location of bloop files
  private val targetDir_ : Property[File] = project.getObjects.property(classOf[File])

  @Input
  @Optional def getTargetDir: Property[File] = targetDir_

  // name of Scala compiler
  private val compilerName_ : Property[String] = project.getObjects.property(classOf[String])

  @Input
  @Optional def getCompilerName: Property[String] = compilerName_

  // name of Scala library
  private val stdLibName_ : Property[String] = project.getObjects.property(classOf[String])

  @Input
  @Optional def getStdLibName: Property[String] = stdLibName_

  // Dotty override
  private val dottyVersion_ : Property[String] = project.getObjects.property(classOf[String])

  @Input
  @Optional def getDottyVersion: Property[String] = dottyVersion_

  private def getDottyVersionOption: Option[String] = if (dottyVersion_.isPresent) Some(dottyVersion_.get) else None

  // include the source artifacts
  // In Gradle 4.3 the default property for Boolean is false (not null) so default has to be set here
  private val includeSources_ : Property[java.lang.Boolean] =
  project.getObjects.property(classOf[java.lang.Boolean])
  includeSources_.set(true)

  @Input
  @Optional def getIncludeSources: Property[java.lang.Boolean] = includeSources_

  // include the Javadoc artifacts
  // In Gradle 4.3 the default property for Boolean is false (not null) so default has to be set here
  private val includeJavadoc_ : Property[java.lang.Boolean] =
  project.getObjects.property(classOf[java.lang.Boolean])
  includeJavadoc_.set(false)

  @Input
  @Optional def getIncludeJavadoc: Property[java.lang.Boolean] = includeJavadoc_

  def createParameters: BloopParameters =
    BloopParameters(
      targetDir_.getOrElse(project.getRootProject.getProjectDir / ".bloop"),
      compilerName_.getOrElse("scala-compiler"),
      stdLibName_.getOrElse("scala-library"),
      includeSources_.get,
      includeJavadoc_.get,
      getDottyVersionOption
    )
}

case class BloopParameters(
                            targetDir: File,
                            compilerName: String,
                            stdLibName: String,
                            includeSources: Boolean,
                            includeJavadoc: Boolean,
                            dottyVersion: Option[String]
                          )
