val jsProject = project
  .enablePlugins(ScalaJSPlugin)
  .settings(
    InputKey[Unit]("check") := {
      val expected = complete.DefaultParsers.spaceDelimited("").parsed.head
      val config = bloopConfigDir.value / s"${thisProject.value.id}.json"
      val lines = IO.read(config).replaceAll("\\s", "")
      assert(lines.contains(s""""platform":{"name":"$expected""""))
      assert(lines.contains(s""""mode":"debug""""))
      assert(lines.contains(s""""version":"0.6.28""""))
    }
  )
