import bloop.integrations.sbt.BloopDefaults

// `app` is a subproject in app/, but it exports a resource that lives outside
// its module root (file1.yml sits at the build root). It is a relative
// single-segment path, which used to make pathsOutsideRoots NPE on the null
// parent, and it exercises the "resource outside the resource roots" path.
val app = project
  .in(file("app"))
  .settings(
    Compile / Keys.unmanagedResources += file("file1.yml")
  )

val bloopConfigFile = settingKey[File]("Config file to test")
bloopConfigFile := {
  val bloopDir = Keys.baseDirectory.value./(".bloop")
  bloopDir./("app.json")
}

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles := {
  val configContents = BloopDefaults.unsafeParseConfig(bloopConfigFile.value.toPath)
  val resources = configContents.project.resources.toList.flatten

  // The resource outside the module root is exported as a standalone, absolute path.
  val externalResource = resources.filter(_.getFileName.toString == "file1.yml")
  assert(
    externalResource.nonEmpty && externalResource.forall(_.isAbsolute),
    s"file1.yml (resource outside the module root) should be a standalone absolute resource: $resources"
  )

  // The module's own resource directory is exported as a directory...
  assert(
    resources.exists(_.endsWith(java.nio.file.Paths.get("app", "src", "main", "resources"))),
    s"app resource directory not found in resources: $resources"
  )

  // ...and a file inside it stays covered by that directory instead of being
  // misclassified as a standalone resource.
  assert(
    !resources.exists(_.getFileName.toString == "in-root.txt"),
    s"in-root resource was wrongly exported as a standalone file: $resources"
  )
}
