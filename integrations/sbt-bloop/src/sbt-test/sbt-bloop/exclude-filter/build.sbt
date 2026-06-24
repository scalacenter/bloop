import bloop.integrations.sbt.BloopDefaults

// Mixes a name-based exclude filter with an arbitrary predicate filter. A
// directory with filtered files is exported as the explicit list of files sbt
// compiles, so neither kind of filter lets bloop compile more than sbt.
val app = project
  .in(file("app"))
  .settings(
    Compile / unmanagedSources / excludeFilter :=
      (Compile / unmanagedSources / excludeFilter).value ||
        "*Excluded.scala" ||
        new sbt.io.SimpleFileFilter(f => f.getName == "bar.scala")
  )

// A narrowed include filter keeps only Scala files; Java files are not exported.
val narrowed = project
  .in(file("narrowed"))
  .settings(
    Compile / unmanagedSources / includeFilter := "*.scala"
  )

// A filter is set but nothing currently matches it. The directory must still be
// exported as explicit files (and a warning printed) so that a matching file
// added later is not picked up until the build is re-imported.
val emptyFiltered = project
  .in(file("emptyFiltered"))
  .settings(
    Compile / unmanagedSources / excludeFilter :=
      (Compile / unmanagedSources / excludeFilter).value || "*Generated.scala"
  )

// Default filters: the exported configuration must keep using directories.
val vanilla = project.in(file("vanilla"))

// Managed sources must never trip the export into listing files.
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
  def names(paths: List[java.nio.file.Path]): Set[String] = paths.map(_.getFileName.toString).toSet

  val appConfig = BloopDefaults.unsafeParseConfig(bloopDir./("app.json").toPath).project
  val appScalaDir = (app / Compile / Keys.scalaSource).value.toPath
  assert(
    appConfig.sourcesGlobs.toList.flatten.isEmpty,
    s"app must not export sources globs: ${appConfig.sourcesGlobs}"
  )
  assert(
    !appConfig.sources.contains(appScalaDir),
    s"directory with filtered sources is still exported as a directory: ${appConfig.sources}"
  )
  val appNames = names(appConfig.sources)
  assert(appNames("Foo.scala"), s"included Foo.scala is missing: ${appConfig.sources}")
  assert(appNames("Keep.scala"), s"included nested Keep.scala is missing: ${appConfig.sources}")
  for (excluded <- List("Excluded.scala", "AlsoExcluded.scala", "bar.scala"))
    assert(!appNames(excluded), s"filtered $excluded was exported: ${appConfig.sources}")

  val narrowedConfig = BloopDefaults.unsafeParseConfig(bloopDir./("narrowed.json").toPath).project
  val narrowedScalaDir = (narrowed / Compile / Keys.scalaSource).value.toPath
  assert(
    narrowedConfig.sourcesGlobs.toList.flatten.isEmpty,
    s"narrowed must not export sources globs: ${narrowedConfig.sourcesGlobs}"
  )
  assert(
    !narrowedConfig.sources.contains(narrowedScalaDir),
    s"narrowed source directory is still exported as a directory: ${narrowedConfig.sources}"
  )
  val narrowedNames = names(narrowedConfig.sources)
  assert(narrowedNames("Foo.scala"), s"included Foo.scala is missing: ${narrowedConfig.sources}")
  assert(!narrowedNames("Legacy.java"), s"Java file was exported: ${narrowedConfig.sources}")
  assert(!narrowedNames(".Broken.scala"), s"hidden file was exported: ${narrowedConfig.sources}")

  // A filter that currently matches no file must still switch the directory to
  // an explicit file list, even though nothing is excluded yet.
  val emptyConfig = BloopDefaults.unsafeParseConfig(bloopDir./("emptyFiltered.json").toPath).project
  val emptyScalaDir = (emptyFiltered / Compile / Keys.scalaSource).value.toPath
  assert(
    emptyConfig.sourcesGlobs.toList.flatten.isEmpty,
    s"emptyFiltered must not export sources globs: ${emptyConfig.sourcesGlobs}"
  )
  assert(
    !emptyConfig.sources.contains(emptyScalaDir),
    s"directory with a filter is still exported as a directory: ${emptyConfig.sources}"
  )
  assert(
    names(emptyConfig.sources)("Foo.scala"),
    s"included Foo.scala is missing: ${emptyConfig.sources}"
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
