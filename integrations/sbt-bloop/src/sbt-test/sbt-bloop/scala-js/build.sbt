val jsProject = project
  .enablePlugins(ScalaJSPlugin)
  .settings(
    InputKey[Unit]("check") := {
      val expected = complete.DefaultParsers.spaceDelimited("").parsed.head
      val config = bloopConfigDir.value / s"${thisProject.value.id}.json"
      val lines = IO.read(config)
      assert(lines.contains(s""""platform" : "$expected""""))
    }
  )
