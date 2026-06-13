import bloop.integrations.sbt.BloopDefaults

// Mixes a name-based filter (exported as glob patterns that also cover future
// files) with an arbitrary predicate filter (exported as the files and
// directories it rejects at export time).
val app = project
  .in(file("app"))
  .settings(
    Compile / unmanagedSources / excludeFilter :=
      (Compile / unmanagedSources / excludeFilter).value ||
        "*Excluded.scala" ||
        new sbt.io.SimpleFileFilter(f =>
          f.getName == "bar.scala" || f.getName == "skipped" || f.getName == "empty-skipped"
        )
  )

// A narrowed name-based include filter is exported as the glob includes of the
// entry, so files of other extensions are ignored even when created later.
val narrowed = project
  .in(file("narrowed"))
  .settings(
    Compile / unmanagedSources / includeFilter := "*.scala"
  )

// Default filters: the exported configuration must not change at all.
val vanilla = project.in(file("vanilla"))

// Managed sources must never trip the export into emitting globs.
val generated = project
  .in(file("generated"))
  .settings(
    Compile / sourceGenerators += Def.task {
      val f = (Compile / sourceManaged).value / "Gen.scala"
      IO.write(f, "object Gen\n")
      Seq(f)
    }
  )

val checkBloopFiles = taskKey[Unit]("Check bloop file contents")
checkBloopFiles := {
  // ThisBuild scoping keeps the path stable when the task runs in subprojects
  val bloopDir = (ThisBuild / Keys.baseDirectory).value./(".bloop")

  val appConfig = BloopDefaults.unsafeParseConfig(bloopDir./("app.json").toPath).project
  val appScalaDir = (app / Compile / Keys.scalaSource).value.toPath
  assert(
    !appConfig.sources.contains(appScalaDir),
    s"directory with filtered sources is still exported as plain: ${appConfig.sources}"
  )
  val globs = appConfig.sourcesGlobs.toList.flatten
  val glob = globs
    .find(_.directory == appScalaDir)
    .getOrElse(sys.error(s"no sources glob for $appScalaDir: $globs"))
  assert(glob.walkDepth.isEmpty, s"unexpected walkDepth: $glob")
  assert(
    glob.includes == List("glob:[!.]*.{scala,java}", "glob:**/[!.]*.{scala,java}"),
    s"unexpected includes: ${glob.includes}"
  )
  val expectedExcludes = List(
    // the name-based filter component, applied to future files and directories too
    "glob:**/*Excluded.scala",
    "glob:**/*Excluded.scala/**",
    "glob:*Excluded.scala",
    "glob:*Excluded.scala/**",
    // the predicate filter component, snapshot at export time
    "glob:bar.scala",
    "glob:empty-skipped/**",
    "glob:skipped/**"
  )
  assert(glob.excludes == expectedExcludes, s"unexpected excludes: ${glob.excludes}")

  val narrowedConfig = BloopDefaults.unsafeParseConfig(bloopDir./("narrowed.json").toPath).project
  val narrowedScalaDir = (narrowed / Compile / Keys.scalaSource).value.toPath
  assert(
    !narrowedConfig.sources.contains(narrowedScalaDir),
    s"directory with a narrowed include filter is still exported as plain: ${narrowedConfig.sources}"
  )
  val narrowedGlob = narrowedConfig.sourcesGlobs.toList.flatten
    .find(_.directory == narrowedScalaDir)
    .getOrElse(sys.error(s"no sources glob for $narrowedScalaDir: ${narrowedConfig.sourcesGlobs}"))
  assert(
    narrowedGlob.includes == List("glob:*.scala", "glob:**/*.scala"),
    s"unexpected includes: ${narrowedGlob.includes}"
  )
  // Only the hidden-file guard: include-rejected files are handled by the
  // includes themselves and hidden files must never become compilable
  assert(
    narrowedGlob.excludes == List("glob:**/.*", "glob:.*"),
    s"unexpected excludes: ${narrowedGlob.excludes}"
  )

  for (name <- List("vanilla", "generated")) {
    val config = BloopDefaults.unsafeParseConfig(bloopDir./(s"$name.json").toPath).project
    assert(
      config.sourcesGlobs.toList.flatten.isEmpty,
      s"$name must not export sources globs: ${config.sourcesGlobs}"
    )
    val scalaDir = ((file(name) / "src" / "main" / "scala").getAbsoluteFile).toPath
    assert(
      config.sources.contains(scalaDir),
      s"$name lost its plain source directory: ${config.sources}"
    )
  }
}
