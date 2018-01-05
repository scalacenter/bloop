name := "with-resources"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % "test"

resourceGenerators in Compile += Def.task {
  val out = resourceManaged.in(Compile).value / "generated-compile-resource.txt"
  IO.write(out, "Content of generated-compile-resource.txt")

  Seq(out)
}.taskValue

resourceGenerators in Test += Def.task {
  val out = resourceManaged.in(Test).value / "generated-test-resource.txt"
  IO.write(out, "Content of generated-test-resource.txt")

  Seq(out)
}.taskValue
