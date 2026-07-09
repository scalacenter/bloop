def checkSettings = List(
  InputKey[Unit]("check") := {
    val args = complete.DefaultParsers.spaceDelimited("").parsed
    val expectedPlatform = args(0)
    val expectedMainJsdom = args(1)
    val expectedTestJsdom = args(2)
    val id = thisProject.value.id
    def stripped(file: File): String = IO.read(file).replaceAll("\\s", "")
    val mainConfig = stripped(bloopConfigDir.value / s"$id.json")
    val testConfig = stripped(bloopConfigDir.value / s"$id-test.json")
    assert(mainConfig.contains(s""""platform":{"name":"$expectedPlatform""""))
    assert(mainConfig.contains(s""""mode":"debug""""))
    assert(mainConfig.contains(s""""version":"1.22.0""""))
    assert(
      mainConfig.contains(s""""jsdom":$expectedMainJsdom"""),
      s"expected jsdom=$expectedMainJsdom in $id.json"
    )
    assert(
      testConfig.contains(s""""jsdom":$expectedTestJsdom"""),
      s"expected jsdom=$expectedTestJsdom in $id-test.json"
    )
  }
)

val jsProject = project
  .enablePlugins(ScalaJSPlugin)
  .settings(checkSettings)

val jsdomProject = project
  .enablePlugins(ScalaJSPlugin)
  .settings(checkSettings)
  .settings(
    Test / jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv()
  )

val jsdomAllProject = project
  .enablePlugins(ScalaJSPlugin)
  .settings(checkSettings)
  .settings(
    jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv()
  )
