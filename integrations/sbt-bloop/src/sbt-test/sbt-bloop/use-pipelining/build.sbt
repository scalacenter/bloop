import bloop.integrations.sbt.BloopDefaults

ThisBuild / usePipelining := true
ThisBuild / scalacOptions += "-Ydebug"
val foo = project
  .in(file(".") / "foo")

val bar = project
  .in(file(".") / "bar")
  .dependsOn(foo)

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")

def readConfigFor(projectName: String, allConfigs: Seq[File]): bloop.config.Config.File = {
  val configFile = allConfigs
    .find(_.toString.endsWith(s"$projectName.json"))
    .getOrElse(sys.error(s"Missing $projectName.json"))
  BloopDefaults.unsafeParseConfig(configFile.toPath)
}

checkBloopFiles in ThisBuild := {
  import java.nio.file.Files
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  val fooConfig = bloopDir./("foo.json")
  val barConfig = bloopDir./("bar.json")

  assert(Files.exists(fooConfig.toPath))
  assert(Files.exists(barConfig.toPath))

  val outputFileFoo = BloopDefaults.unsafeParseConfig(fooConfig.toPath)
  val outputFileBar = BloopDefaults.unsafeParseConfig(barConfig.toPath)

  val optsFoo = outputFileFoo.project.scala.get.options
  assert(optsFoo == List("-Ydebug"), s"Expected `List(-Ydebug)` in scala options, got `$optsFoo`")

  val optsBar = outputFileBar.project.scala.get.options
  assert(optsBar == List("-Ydebug"), s"Expected `List(-Ydebug)` in scala options, got `$optsBar`")

}
