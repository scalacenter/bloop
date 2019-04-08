bloopExportJarClassifiers in Global := Some(Set("sources"))
bloopConfigDir in Global := baseDirectory.value / "bloop-config"

val a = project
val b = project.dependsOn(a)
