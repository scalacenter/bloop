import java.nio.file.Paths
import bloop.integrations.sbt.BloopDefaults

ThisBuild / scalaVersion := "3.3.6"
ThisBuild / semanticdbEnabled := true

val foo = project.in(file("."))

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles := {
  val bloopDir = Keys.baseDirectory.value / ".bloop"
  val configFile = bloopDir / "foo.json"
  val config = BloopDefaults.unsafeParseConfig(configFile.toPath)
  val options = config.project.scala.get.options

  // sbt 2.x placeholders such as ${OUT} must be resolved
  val unresolved = options.filter(_.contains("${"))
  assert(unresolved.isEmpty, s"Unresolved placeholders in scalac options: $unresolved")

  val targetIndex = options.indexOf("-semanticdb-target")
  assert(targetIndex >= 0, s"Missing -semanticdb-target in $options")
  val target = Paths.get(options(targetIndex + 1))
  assert(target.isAbsolute, s"SemanticDB target is not absolute: $target")
  val base = Keys.baseDirectory.value.toPath
  assert(
    target.startsWith(base) || target.startsWith(base.toRealPath()),
    s"SemanticDB target $target is not inside $base"
  )
}
