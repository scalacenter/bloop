val foo = project.in(file("."))

def metaConfig(rootBase: File): File =
  rootBase / "project" / ".bloop" / s"${rootBase.getName}-build.json"

def mtimeFile(rootBase: File): File = rootBase / "target" / "meta-mtime.txt"

val checkMetaPresent = taskKey[Unit]("Assert the meta-build config exists")
checkMetaPresent := {
  val cfg = metaConfig((ThisBuild / baseDirectory).value)
  assert(cfg.exists(), s"Expected meta-build config at $cfg")
}

val recordMetaMtime = taskKey[Unit]("Record the meta-build config mtime")
recordMetaMtime := {
  val root = (ThisBuild / baseDirectory).value
  IO.write(mtimeFile(root), metaConfig(root).lastModified().toString)
}

val checkMetaUnchanged = taskKey[Unit]("Assert the meta-build export was skipped")
checkMetaUnchanged := {
  val root = (ThisBuild / baseDirectory).value
  val recorded = IO.read(mtimeFile(root)).trim.toLong
  val current = metaConfig(root).lastModified()
  assert(
    current == recorded,
    s"Expected export to be skipped (recorded=$recorded current=$current)"
  )
}

val checkMetaRegenerated = taskKey[Unit]("Assert the meta-build export was regenerated")
checkMetaRegenerated := {
  val root = (ThisBuild / baseDirectory).value
  val recorded = IO.read(mtimeFile(root)).trim.toLong
  val current = metaConfig(root).lastModified()
  assert(
    current > recorded,
    s"Expected export to be regenerated (recorded=$recorded current=$current)"
  )
}
