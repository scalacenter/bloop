import bloop.integrations.sbt.BloopDefaults

val providedRuntimeClasspath = project
  .in(file("."))
  .settings(
    libraryDependencies ++= List(
      "com.google.code.gson" % "gson" % "2.10" % Provided,
      // slf4j-api 1.7.25 reaches the compile classpath directly and through slf4j-simple.
      // logback-classic needs 1.7.32, so Runtime resolves the newer version.
      "org.slf4j" % "slf4j-api" % "1.7.25",
      "org.slf4j" % "slf4j-simple" % "1.7.25" % Provided,
      "ch.qos.logback" % "logback-classic" % "1.2.7" % Runtime
    )
  )

val bloopConfigFile = settingKey[File]("Config file to test")
bloopConfigFile := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  bloopDir./("providedRuntimeClasspath.json")
}

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles := {
  val configContents = BloopDefaults.unsafeParseConfig(bloopConfigFile.value.toPath)

  assert(configContents.project.platform.isDefined)
  val platformJvm =
    configContents.project.platform.get.asInstanceOf[bloop.config.Config.Platform.Jvm]
  val runtimeClasspathNames = platformJvm.classpath.map(_.map(_.getFileName.toString))

  assert(
    runtimeClasspathNames.exists(_.contains("gson-2.10.jar")),
    s"Provided dependency gson should be in runtime classpath, got: $runtimeClasspathNames"
  )

  assert(
    configContents.project.classpath.map(_.getFileName.toString).contains("gson-2.10.jar"),
    "Provided dependency gson should be in compile classpath"
  )

  // Only the version that Runtime resolved may reach the runtime classpath. An evicted compile
  // version is not a Provided dependency.
  val runtimeSlf4jApi = runtimeClasspathNames.toList.flatten.filter(_.startsWith("slf4j-api"))
  assert(
    runtimeSlf4jApi == List("slf4j-api-1.7.32.jar"),
    s"Runtime classpath should hold only the Runtime version of slf4j-api, got: $runtimeSlf4jApi"
  )
  assert(
    runtimeClasspathNames.exists(_.contains("slf4j-simple-1.7.25.jar")),
    s"Provided dependency slf4j-simple should be in runtime classpath, got: $runtimeClasspathNames"
  )
  val compileClasspathNames = configContents.project.classpath.map(_.getFileName.toString)
  assert(
    compileClasspathNames.contains("slf4j-api-1.7.25.jar"),
    s"Compile classpath should keep slf4j-api 1.7.25, got: $compileClasspathNames"
  )
}
