val foo = project.in(file("."))

def bloopJsons(dir: File): Seq[File] =
  Option(dir.listFiles()).toList.flatten.filter { f =>
    f.isFile && f.getName.endsWith(".json") && f.getName != "bloop.settings.json"
  }

val metaBloopDir = settingKey[File]("Outer meta-build .bloop dir")
metaBloopDir := (ThisBuild / baseDirectory).value / "project" / ".bloop"

val metaMetaBloopDir = settingKey[File]("Nested meta-build .bloop dir")
metaMetaBloopDir := (ThisBuild / baseDirectory).value / "project" / "project" / ".bloop"

val checkExportsAbsent = taskKey[Unit]("Assert neither meta-build layer was exported")
checkExportsAbsent := {
  val meta = bloopJsons(metaBloopDir.value)
  val metaMeta = bloopJsons(metaMetaBloopDir.value)
  assert(meta.isEmpty, s"Expected no outer meta-build export, found: $meta")
  assert(metaMeta.isEmpty, s"Expected no nested meta-build export, found: $metaMeta")
}

val checkExportsPresent = taskKey[Unit]("Assert both meta-build layers were exported")
checkExportsPresent := {
  assert(bloopJsons(metaBloopDir.value).nonEmpty, "Expected the outer meta-build to be exported")
  assert(
    bloopJsons(metaMetaBloopDir.value).nonEmpty,
    "Expected the nested meta-build to be exported"
  )
}
