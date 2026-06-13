import java.nio.file.Files

// Mirrors how Akka's sbt-multi-jvm plugin declares its configuration.
val MultiJvm = config("multi-jvm").extend(Test)

val foo = project
  .in(file("foo"))
  .configs(MultiJvm)
  .settings(
    // Set up the standard sbt settings for the multi-jvm configuration (sources,
    // products, compile) just like sbt-multi-jvm does. Crucially we do NOT wire
    // any bloop settings here: bloop must export this configuration automatically.
    inConfig(MultiJvm)(Defaults.testSettings)
  )

val checkBloopFile = taskKey[Unit]("Check bloop file contents")
ThisBuild / checkBloopFile := {
  val bloopDir = (ThisBuild / baseDirectory).value / ".bloop"
  val fooConfig = bloopDir / "foo.json"
  val fooTestConfig = bloopDir / "foo-test.json"
  val fooMultiJvmConfig = bloopDir / "foo-multi-jvm.json"

  List(fooConfig, fooTestConfig, fooMultiJvmConfig).foreach { f =>
    assert(Files.exists(f.toPath), s"Missing config file for $f.")
  }

  def readBareFile(p: java.nio.file.Path): String =
    new String(Files.readAllBytes(p)).replaceAll("\\s", "")

  val contents = readBareFile(fooMultiJvmConfig.toPath)

  // multi-jvm extends Test, so its target depends on foo (compile) and foo-test
  assert(
    contents.contains(""""dependencies":["foo","foo-test"]"""),
    s"Unexpected dependencies in foo-multi-jvm.json: $contents"
  )

  // multi-jvm holds test code, so the target is tagged as test
  assert(
    contents.contains(""""tags":["test"]"""),
    s"foo-multi-jvm.json should be tagged as test: $contents"
  )

  // The multi-jvm source directory must be exported. Normalize Windows
  // separators so the check is platform independent.
  val normalized = contents.replace("\\\\", "/")
  assert(
    normalized.contains("src/multi-jvm/scala"),
    s"multi-jvm source directory missing in foo-multi-jvm.json: $contents"
  )
}
