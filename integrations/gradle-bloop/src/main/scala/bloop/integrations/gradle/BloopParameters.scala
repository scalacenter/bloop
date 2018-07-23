package bloop.integrations.gradle

import java.io.File

import org.gradle.api.Project
import syntax._

/**
 * Project extension to configure Bloop.
 *
 * For each property we have a private variable and a set of interface functions to support
 * convenient access from Scala, Java and the Groovy DSL.
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
  def targetDir: File = getTargetDir
  def targetDir(value: File): Unit = setTargetDir(value)
  def targetDir(path: String): Unit = setTargetDir(new File(path))
  def setTargetDir(value: File): Unit = {
    targetDir_ = value
  }

  // mainSourceSet
  private var mainSourceSet_ : String = "main"
  def getMainSourceSet: String = mainSourceSet_
  def mainSourceSet: String = getMainSourceSet
  def mainSourceSet(value: String): Unit = setMainSourceSet(value)
  def setMainSourceSet(value: String): Unit = {
    mainSourceSet_ = value
  }

  // compiler name
  private var compilerName_ : String = "scala-compiler"
  def getCompilerName: String = compilerName_
  def compilerName: String = getCompilerName
  def compilerName(value: String): Unit = setCompilerName(value)
  def setCompilerName(value: String): Unit = {
    compilerName_ = value
  }

  // standard library name
  private var stdLibName_ : String = "scala-library"
  def getStdLibName: String = stdLibName_
  def stdLibName: String = getStdLibName
  def stdLibName(value: String): Unit = setStdLibName(value)
  def setStdLibName(value: String): Unit = {
    stdLibName_ = value
  }
}
