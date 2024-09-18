val foo = project
  .in(file(".") / "foo")

val bar = project
  .in(file(".") / "bar")
  .settings(
    bspEnabled := false
  )
  .dependsOn(foo)

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles in ThisBuild := {
  import java.nio.file.Files
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val fooConfig = bloopDir./("foo.json")
  val barConfig = bloopDir./("bar.json")
  val barTestConfig = bloopDir./("bar-test.json")
  val fooTestConfig = bloopDir./("foo-test.json")

  assert(Files.exists(fooConfig.toPath))
  assert(Files.exists(fooTestConfig.toPath))
  assert(!Files.exists(barConfig.toPath))
  assert(!Files.exists(barTestConfig.toPath))
}
