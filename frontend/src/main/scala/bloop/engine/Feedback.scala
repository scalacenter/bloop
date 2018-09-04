package bloop.engine

import bloop.Project

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

  def failedToLink(project: Project, linker: String, t: Throwable): String =
    s"Failed to link $linker project '${project.name}': '${t.getMessage}'"
  def missingLinkArtifactFor(project: Project, artifactName: String, linker: String): String =
    s"Missing $linker's artifact $artifactName for project '$project' (resolution failed)"
  def noLinkFor(project: Project): String =
    s"Cannot link JVM project '${project.name}', `link` is only available in Scala Native or Scala.js projects."

  implicit class XMessageString(msg: String) {
    def suggest(suggestion: String): String = s"$msg\n$suggestion"
  }
}
