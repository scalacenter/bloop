package bloop.engine
import java.nio.file.Path

import bloop.data.Project
import bloop.engine.Dag.RecursiveTrace
import bloop.io.AbsolutePath
import bloop.exec.JavaEnv

object Feedback {
  private final val eol = System.lineSeparator
  def listMainClasses(mainClasses: List[String]): String =
    s"Use the following main classes:\n${mainClasses.mkString(" * ", s"$eol * ", "")}"
  def missingMainClass(project: Project): String =
    s"No main classes defined in project '${project.name}'"
  def expectedDefaultMainClass(project: Project): String =
    s"Multiple main classes found. Expected a default main class via command-line or in the configuration of project '${project.name}'"

  def missingScalaInstance(project: Project): String =
    s"Failed to compile project '${project.name}': found Scala sources but project is missing Scala configuration."
  def missingInstanceForJavaCompilation(project: Project): String =
    s"Failed to compile Java sources in ${project.name}: default Zinc Scala instance couldn't be created!"

  def reportRecursiveTrace(trace: RecursiveTrace): String = {
    trace.visited.headOption match {
      case Some(initialProject) =>
        s"Fatal recursive dependency detected in '${initialProject}': ${trace.visited}"
      case None => "The build has an undefined recursive trace. Please, report upstream."
    }
  }

  def detectMissingDependencies(projectName: String, missing: List[String]): Option[String] = {
    missing match {
      case Nil => None
      case missing :: Nil =>
        Some(
          s"Missing project '$missing' may cause compilation issues in project '${projectName}'"
        )
      case xs =>
        val deps = missing.map(m => s"'$m'").mkString(", ")
        Some(s"Missing projects $deps, may cause compilation issues in project '${projectName}'")
    }
  }

  def failedToLink(project: Project, linker: String, t: Throwable): String =
    s"Failed to link $linker project '${project.name}': '${t.getMessage}'"
  def missingLinkArtifactFor(project: Project, artifactName: String, linker: String): String =
    s"Missing $linker's artifact $artifactName for project '$project' (resolution failed)"
  def noLinkFor(project: Project): String =
    s"Cannot link JVM project '${project.name}', `link` is only available in Scala Native or Scala.js projects."

  def printException(error: String, t: Throwable): String = {
    val msg = t.getMessage
    val cause = t.getCause
    if (msg == null) {
      s"$error: '$cause'"
    } else {
      if (cause == null) s"$error: '$t'"
      else {
        s"""
           |$error: '$t'
           |  Caused by: '$cause'
       """.stripMargin
      }
    }
  }

  def missingConfigDirectory(configDirectory: AbsolutePath): String =
    s"""Missing configuration directory in $configDirectory.
       |
       |  1. Did you run bloop outside of the working directory of your build?
       |     If so, change your current directory or point directly to your `.bloop` directory via `--config-dir`.
       |
       |  2. Did you forget to generate configuration files for your build?
       |     Check the installation instructions https://scalacenter.github.io/bloop/setup
    """.stripMargin

  val MissingPipeName = "Missing pipe name to establish a local connection in Windows"
  val MissingSocket =
    "A socket file is required to establish a local connection through Unix sockets"
  def excessiveSocketLengthInMac(socket: Path): String =
    s"The length of the socket path '${socket.toString}' exceeds 104 bytes in macOS"
  def excessiveSocketLength(socket: Path): String =
    s"The length of the socket path '${socket.toString}' exceeds 108 bytes"
  def existingSocketFile(socket: Path): String =
    s"Bloop bsp server cannot establish a connection with an existing socket file '${socket.toAbsolutePath}'"
  def missingParentOfSocket(socket: Path): String =
    s"'${socket.toAbsolutePath}' cannot be created because its parent does not exist"
  def unexpectedPipeFormat(pipeName: String): String =
    s"Pipe name '${pipeName}' does not start with '\\\\.\\pipe\\'"
  def outOfRangePort(n: Int): String =
    s"Port number '${n}' is either negative or bigger than 65535"
  def reservedPortNumber(n: Int): String =
    s"Port number '${n}' is reserved for the operating system. Use a port number bigger than 1024"
  def unknownHostName(host: String): String =
    s"Host name '$host' could not be either parsed or resolved"

  def pprint(projects: Traversable[Project]): String =
    projects.map(p => s"'${p.name}'").mkString(", ")
  def skippedUnsupportedScalaMetals(scalaVersion: String): String =
    s"Skipped configuration of SemanticDB in unsupported $scalaVersion projects"
  def configuredMetalsProjects(projects: Traversable[Project]): String =
    s"Configured SemanticDB in projects ${pprint(projects)}"
  def failedMetalsConfiguration(version: String, cause: String): String =
    s"Stopped configuration of SemanticDB in Scala $version projects: $cause"

  def detectedJdkWithoutJDI(error: Throwable): String = {
    s"""Debugging is not supported because Java Debug Interface couldn't be resolved in detected JDK ${JavaEnv.DefaultJavaHome}: '${error.getMessage}'. To enable it, check manually that you're running on a JDK and JDI is supported.
       |
       |Run bloop about for more information about the current JDK runtime.""".stripMargin
  }

  def detectedUnsupportedJreForDebugging(error: Throwable): String = {
    s"""Debugging is not supported because bloop server is running on a JRE ${JavaEnv.DefaultJavaHome} with no support for Java Debug Interface: '${error.getMessage}'. To enable debugging, install a JDK and restart the server.
       |
       |Run bloop about for more information about the current JDK runtime.""".stripMargin
  }

  implicit class XMessageString(msg: String) {
    def suggest(suggestion: String): String = s"$msg\n$suggestion"
  }
}
