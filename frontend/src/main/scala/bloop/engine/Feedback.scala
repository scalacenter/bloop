package bloop.engine

import bloop.Project
import bloop.io.AbsolutePath

object Feedback {
  private final val eol = System.lineSeparator
  def listMainClasses(mainClasses: List[String]): String =
    s"Use the following main classes:\n${mainClasses.mkString(" * ", s"$eol * ", "")}"
  def missingMainClass(project: Project, mainClass: String = ""): String =
    s"Missing main class $mainClass in project '${project.name}'"
  def missingDefaultMainClass(project: Project, mainClass: String): String =
    s"Missing default main class $mainClass in project '${project.name}'"
  def expectedMainClass(project: Project): String =
    s"Expected a main class via command-line or in the configuration of project '${project.name}'"

  def missingScalaInstance(project: Project): String =
    s"Failed to compile project '${project.name}': found Scala sources but project is missing Scala configuration."
  def missingInstanceForJavaCompilation(project: Project): String =
    s"Failed to compile Java sources in ${project.name}: default Zinc Scala instance couldn't be created!"

  def failedToLink(project: Project, linker: String, t: Throwable): String =
    s"Failed to link $linker project '${project.name}': '${t.getMessage}'"
  def missingLinkArtifactFor(project: Project, artifactName: String, linker: String): String =
    s"Missing $linker's artifact $artifactName for project '$project' (resolution failed)"
  def noLinkFor(project: Project): String =
    s"Cannot link JVM project '${project.name}', `link` is only available in Scala Native or Scala.js projects."


  def missingConfigDirectory(configDirectory: AbsolutePath): String =
    s"""Missing configuration directory in $configDirectory.
       |
       |  1. Did you run bloop outside of the working directory of your build?
       |     If so, change your current directory or point to your build directory via `--config-dir`.
       |
       |  2. Did you forget to generate configuration files for your build?
       |     Check the installation instructions https://scalacenter.github.io/bloop/docs/installation/
    """.stripMargin

  implicit class XMessageString(msg: String) {
    def suggest(suggestion: String): String = s"$msg\n$suggestion"
  }
}
