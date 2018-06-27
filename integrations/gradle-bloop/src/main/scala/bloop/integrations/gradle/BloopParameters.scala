package bloop.integrations.gradle

import java.io.File

import org.gradle.api.Project
import syntax._

/**
  * Project extension for bloop-specific configurations
  *
  * For each property we have a private variable and a set of interface functions to support convenient access
  * from Scala, Java and the Groovy DSL.
  *
  * From the build definitions it can be used like the following:
  *
  * {{{
  * bloop {
  *   targetDir "$projectDir/.bloop"
  *   mainSourceSet "main"
  *   compilerName "scala-compiler"
  * }
  * }}}
  */
class BloopParameters(project: Project) {
  // targetDir
  private var targetDir_ : File = project.getRootProject.getProjectDir / ".bloop"

  def getTargetDir: File = targetDir_

  def setTargetDir(value: File): Unit = {
    targetDir_ = value
  }

  def targetDir: File = getTargetDir

  def targetDir(value: File): Unit =
    setTargetDir(value)

  def targetDir(path: String): Unit =
    setTargetDir(new File(path))


  // mainSourceSet
  private var mainSourceSet_ : String = "main"

  def getMainSourceSet: String =
    mainSourceSet_

  def setMainSourceSet(value: String): Unit = {
    mainSourceSet_ = value
  }

  def mainSourceSet: String = getMainSourceSet
  def mainSourceSet(value: String): Unit = setMainSourceSet(value)


  // compiler name
  private var compilerName_ : String = "scala-compiler"

  def getCompilerName: String =
    compilerName_

  def setCompilerName(value: String): Unit = {
    compilerName_ = value
  }

  def compilerName: String = getCompilerName
  def compilerName(value: String): Unit = setCompilerName(value)
}
