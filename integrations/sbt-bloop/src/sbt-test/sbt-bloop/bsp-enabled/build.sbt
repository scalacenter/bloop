val foo = project
  .in(file(".") / "foo")

val bar = project
  .in(file(".") / "bar")
  .settings(
    bspEnabled := false
  )
  .dependsOn(foo)
