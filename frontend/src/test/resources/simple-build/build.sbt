bloopExportJarClassifiers in Global := Some(Set("sources"))
bloopConfigDir in Global := baseDirectory.value / "bloop-config"

val a = project.settings(
  libraryDependencies += "com.lihaoyi" %% "sourcecode" % "0.4.2"
)
val b = project.dependsOn(a)
