import bloop.integrations.sbt.BloopDefaults
import sbt.internal.util.Attributed

// A project with actual sources that produces a jar
val lib = project
  .in(file("lib"))

// A sourceless facade that overrides exportedProducts to point to lib's jar
// This simulates the assembly/shading pattern (e.g. sbt's lmCoursierShadedPublishing)
val libFacade = project
  .in(file("target/facade-module"))
  .settings(
    Compile / packageBin := (lib / Compile / packageBin).value,
    Compile / exportedProducts := Seq(Attributed.blank((Compile / packageBin).value))
  )

// A project that depends on the facade
val app = project
  .in(file("app"))
  .dependsOn(libFacade)

def readConfigFor(projectName: String, bloopDir: File): bloop.config.Config.File = {
  val configFile = bloopDir / s"$projectName.json"
  assert(configFile.exists, s"Missing $configFile")
  BloopDefaults.unsafeParseConfig(configFile.toPath)
}

val expectedJarName = settingKey[String]("Expected jar file name from lib's packageBin")
expectedJarName := s"lib_${scalaBinaryVersion.value}-${version.value}.jar"

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles := {
  val bloopDir = Keys.baseDirectory.value / ".bloop"
  val jarName = expectedJarName.value
  val appConfig = readConfigFor("app", bloopDir)
  val classpathNames = appConfig.project.classpath.map(_.getFileName.toString)

  // The facade's jar (from lib's packageBin) must be on app's compile classpath
  assert(
    classpathNames.contains(jarName),
    s"app classpath should contain '$jarName', got:\n${classpathNames.mkString("\n")}"
  )

  // The jar must also be on the runtime classpath
  assert(appConfig.project.platform.isDefined, "app should have a platform defined")
  val runtimeClasspathNames = appConfig.project.platform.get
    .asInstanceOf[bloop.config.Config.Platform.Jvm]
    .classpath
    .getOrElse(Nil)
    .map(_.getFileName.toString)
  assert(
    runtimeClasspathNames.contains(jarName),
    s"app runtime classpath should contain '$jarName', got:\n${runtimeClasspathNames.mkString("\n")}"
  )

  // The facade should have no real sources
  val facadeConfig = readConfigFor("libFacade", bloopDir)
  assert(
    facadeConfig.project.sources.isEmpty ||
      facadeConfig.project.sources.forall(p => !java.nio.file.Files.exists(p)),
    s"Facade should have no actual sources, got: ${facadeConfig.project.sources}"
  )
}
