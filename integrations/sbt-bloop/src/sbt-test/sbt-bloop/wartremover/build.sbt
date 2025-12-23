import java.nio.file.Paths
import bloop.integrations.sbt.BloopDefaults

val foo = project
  .in(file(".") / "foo")
  .settings(
    wartremoverWarnings := Warts.all
  )

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles := {
  if (Keys.name.value != "foo") {
    import java.nio.file.Files
    val bloopDir = Keys.baseDirectory.value./(".bloop")
    val fooConfigFile = bloopDir./("foo.json")

    val fooConfig = BloopDefaults.unsafeParseConfig(fooConfigFile.toPath)

    val pluginPath =
      fooConfig.project.scala.get.options
        .find(_.startsWith("-Xplugin"))
        .get
        .stripPrefix("-Xplugin:")
    assert(Paths.get(pluginPath).toFile.exists())
  }
}
