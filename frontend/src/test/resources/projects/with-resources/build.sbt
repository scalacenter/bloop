name := "with-resources"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % "test"

List(Compile, Test).flatMap(inConfig(_) {
  resourceGenerators += Def.task {
    val configName = configuration.value.name
    val fileName = s"generated-$configName-resource.txt"
    val out = resourceManaged.value / fileName
    IO.write(out, s"Content of $fileName")
    Seq(out)
  }.taskValue
})
