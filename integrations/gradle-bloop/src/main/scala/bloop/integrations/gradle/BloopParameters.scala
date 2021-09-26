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
 *   stdLibName = "scala-library" // or "scala3-library_3"
 *   includeSources = true
 *   includeJavaDoc = false
 * }
 * }}}
 */
case class BloopParametersExtension(project: Project) {
  // Variable names are derived by Gradle from the annotated getter method

  // location of bloop files
  private val targetDir_ : Property[File] = project.getObjects.property(classOf[File])
  @Input @Optional def getTargetDir: Property[File] = targetDir_

  // name of Scala compiler
  private val compilerName_ : Property[String] = project.getObjects.property(classOf[String])
  @Input @Optional def getCompilerName: Property[String] = compilerName_

  // name of Scala library
  private val stdLibName_ : Property[String] = project.getObjects.property(classOf[String])
  @Input @Optional def getStdLibName: Property[String] = stdLibName_

  // include the source artifacts
  // In Gradle 4.3 the default property for Boolean is false (not null) so default has to be set here
  private val includeSources_ : Property[java.lang.Boolean] =
    project.getObjects.property(classOf[java.lang.Boolean])
  includeSources_.set(true)
  @Input @Optional def getIncludeSources: Property[java.lang.Boolean] = includeSources_

  // include the Javadoc artifacts
  // In Gradle 4.3 the default property for Boolean is false (not null) so default has to be set here
  private val includeJavadoc_ : Property[java.lang.Boolean] =
    project.getObjects.property(classOf[java.lang.Boolean])
  includeJavadoc_.set(false)
  @Input @Optional def getIncludeJavadoc: Property[java.lang.Boolean] = includeJavadoc_

  def createParameters: BloopParameters = {
    val defaultTargetDir = project.getRootProject.workspacePath.resolve(".bloop").toFile
    BloopParameters(
      targetDir_.getOrElse(defaultTargetDir),
      Option(compilerName_.getOrNull),
      Option(stdLibName_.getOrNull),
      includeSources_.get,
      includeJavadoc_.get
    )
  }
}

case class BloopParameters(
    targetDir: File,
    compilerName: Option[String],
    stdLibName: Option[String],
    includeSources: Boolean,
    includeJavadoc: Boolean
)
