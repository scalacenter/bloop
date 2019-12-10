import java.nio.file.Paths

val hydraProject = project
  .settings(
    InputKey[Unit]("check") := {
      val home = sys.props("user.home")
      val hydraLicense = Paths.get(home, ".triplequote", "hydra.license").toFile
      if (hydraLicense.isFile) {
        val config = bloopConfigDir.value / s"${thisProject.value.id}.json"
        val lines = IO.read(config).replaceAll("\\s", "")
        assert(lines.contains(s"""scala-compiler-${scalaVersion.value}-hydra"""))
        assert(lines.contains(s"""scala-reflect-${scalaVersion.value}-hydra"""))
        assert(lines.contains(s"""hydra_${scalaVersion.value}"""))
      }
      else {
        sLog.value.info(s"Test is skipped because $hydraLicense doesn't exist")
      }
    }
  )
