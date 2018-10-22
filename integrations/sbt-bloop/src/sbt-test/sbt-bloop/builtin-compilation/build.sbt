import java.nio.file.Files

val foo = project
  .settings(
    libraryDependencies ++= List(
      "org.scalatest" %% "scalatest" % "3.0.5",
      "io.circe" %% "circe-core" % "0.9.3",
      "io.circe" %% "circe-generic" % "0.9.3",
      "org.scalameta" %% "scalameta" % "4.0.0-M2"
    ),
    sources.in(Test) += baseDirectory.in(ThisBuild).value./("StraySourceFile.scala")
  )

val bar = project.dependsOn(foo % "test->compile;test->test")
val baz = project.dependsOn(bar % "compile->test")
val woo = project.dependsOn(foo % "test->compile")
val zaz = project.dependsOn(foo)

val allBloopConfigFiles = settingKey[List[File]]("All config files to test")
allBloopConfigFiles in ThisBuild := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val fooConfig = bloopDir./("foo.json")
  val fooTestConfig = bloopDir./("foo-test.json")
  val barConfig = bloopDir./("bar.json")
  val barTestConfig = bloopDir./("bar-test.json")
  val bazConfig = bloopDir./("baz.json")
  val bazTestConfig = bloopDir./("baz-test.json")
  val wooConfig = bloopDir./("woo.json")
  val wooTestConfig = bloopDir./("woo-test.json")
  val zazConfig = bloopDir./("zaz.json")
  val zazTestConfig = bloopDir./("zaz-test.json")
  List(
    fooConfig,
    fooTestConfig,
    barConfig,
    barTestConfig,
    bazConfig,
    bazTestConfig,
    wooConfig,
    wooTestConfig,
    zazConfig,
    zazTestConfig
  )
}

val checkBloopFile = taskKey[Unit]("Check bloop file contents")
checkBloopFile in ThisBuild := {
  val allConfigs = allBloopConfigFiles.value
  allConfigs.foreach(f => assert(Files.exists(f.toPath), s"Missing config file for ${f}."))
}
