import bloop.integrations.sbt.BloopDefaults

val runtimeDependency = project
  .in(file("."))
  .settings(
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.7" % Runtime
  )

val bloopConfigFile = settingKey[File]("Config file to test")
bloopConfigFile := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val config = bloopDir./("runtimeDependency.json")
  config
}

val bloopTestConfigFile = settingKey[File]("Test config file to test")
bloopTestConfigFile := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val config = bloopDir./("runtimeDependency-test.json")
  config
}

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles := {
  // Jar file names carry the Scala version and the resolver's layout, and their order on the
  // classpath is not stable across sbt 1 and sbt 2. Assert on where the runtime-only dependency
  // ends up rather than on an exact listing.
  val runtimeOnlyJars =
    List("logback-classic-1.2.7.jar", "logback-core-1.2.7.jar", "slf4j-api-1.7.32.jar")

  val configContents = BloopDefaults.unsafeParseConfig(bloopConfigFile.value.toPath)

  assert(configContents.project.platform.isDefined, "Compile config has no platform")
  val platformJvm =
    configContents.project.platform.get.asInstanceOf[bloop.config.Config.Platform.Jvm]
  val runtimeClasspath = platformJvm.classpath.toList.flatten.map(_.getFileName.toString)

  assert(
    runtimeClasspath.headOption.contains("classes"),
    s"Own classes directory should lead the runtime classpath, got: $runtimeClasspath"
  )
  assert(
    runtimeOnlyJars.forall(runtimeClasspath.contains),
    s"Runtime-only dependencies missing from the runtime classpath, got: $runtimeClasspath"
  )

  val compileClasspath = configContents.project.classpath.map(_.getFileName.toString)
  assert(
    runtimeOnlyJars.forall(jar => !compileClasspath.contains(jar)),
    s"Runtime-only dependencies must not be on the compile classpath, got: $compileClasspath"
  )

  // Runtime-only dependencies must reach `resolution`, otherwise their source jars are
  // invisible to `buildTarget/dependencySources` and to the debugger's source lookup.
  val resolvedModules =
    configContents.project.resolution.toList.flatMap(_.modules.map(_.name)).sorted
  assert(
    resolvedModules.contains("logback-classic"),
    s"Runtime-only dependency missing from resolution, got: $resolvedModules"
  )

  // The test configuration already sees runtime dependencies through its own compile classpath,
  // so it gets no separate runtime classpath.
  val configTestContents = BloopDefaults.unsafeParseConfig(bloopTestConfigFile.value.toPath)
  assert(configTestContents.project.platform.isDefined, "Test config has no platform")
  val testPlatformJvm =
    configTestContents.project.platform.get.asInstanceOf[bloop.config.Config.Platform.Jvm]
  assert(
    testPlatformJvm.classpath.isEmpty,
    s"Test config should carry no runtime classpath, got: ${testPlatformJvm.classpath}"
  )

  val testClasspath = configTestContents.project.classpath.map(_.getFileName.toString)
  assert(
    runtimeOnlyJars.forall(testClasspath.contains),
    s"Test compile classpath should contain the runtime dependencies, got: $testClasspath"
  )
}
