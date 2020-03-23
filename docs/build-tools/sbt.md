---
id: sbt
title: sbt
sidebar_label: sbt
---

<!-- start -->

## Install the plugin

Here is a list of the latest Bloop stable and development versions for `sbt-bloop`.

```scala mdoc:releases
I am going to be replaced by the docs infrastructure.
```


Install bloop by adding the following line to your `project/plugins.sbt`:

```sbt
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "@VERSION@")
```

After that, start up sbt or reload your sbt shell to load the plugin.

## Export your build

The sbt command `bloopInstall` exports your sbt build to bloop.

In bloop, an sbt project is represented as a pair of `(sbt project, sbt configuration)` and it's
written to a configuration directory. The default location of this directory in your workspace is
`.bloop/` (you may want to add `.bloop/` to your `.gitignore` file).

For example, a build with a single project `foo` generates two configuration files by
default (one per `Compile` and `Test` configuration):

```bash
$ sbt bloopInstall
[info] Loading global plugins from /Users/John/.sbt/1.0/plugins
(...)
[success] Generated '/disk/foo/.bloop/foo.json'.
[success] Generated '/disk/foo/.bloop/foo-test.json'.
```

where:
1. `foo` comes from the `Compile` sbt configuration; and,
1. `foo-test` comes from the `Test` sbt configuration and depends on `foo`

If a build enables the `IntegrationTest` configuration in a project, Bloop will also export it.

Any change in the build affecting the semantics of a project (such as adding a new library
dependency, a resource or changing the project name) requires the user to run `bloopInstall`. When a
project is removed in the build, `bloopInstall` will automatically remove its old configuration from
the `.bloop` directory.

## Verify installation and export

Verify your installation by running `bloop projects` in the root of the sbt workspace directory.

```bash
$ bloop projects
foo
foo-test
```

If the results of `bloop projects` is empty, check that:

1. You are running the command-line invocation in the root base directory (e.g. `/disk/foo`).
1. The sbt build export process completed successfully.
1. The `.bloop/` configuration directory contains bloop configuration files.

If you suspect bloop is loading the configuration files from somewhere else, use `--verbose`:

```bash
$ bloop projects --verbose
[D] Projects loaded from '/my-project/.bloop':
foo
foo-test
```

Here's a list of bloop commands you can run next to start playing with bloop:

1. `bloop compile --help`: shows the help section for compile.
1. `bloop compile foo-test`: compiles foo's `src/main` and `src/test`.
1. `bloop test foo-test -w`: runs foo tests repeatedly with file watching enabled.

<!-- end -->

## Next steps after installation

Use an IDE such as [Metals](docs/ides/metals) or
[IntelliJ](docs/ides/intellij) to write code or play with the
[CLI](docs/cli/tutorial) if you want to explore what CLI options are
available.

If you need help, you can always come over our [Gitter
channel](https://gitter.im/scalacenter/bloop).

## Plugin Configuration

### Don't export certain targets

`bloopInstall` exports every project in your build. To disable exporting a project, you can
customize the result of the `bloopGenerate` task (which is scoped at the configuration level).

```scala
val foo = project
  .settings(
    // Bloop will not generate a target for the compile configuration of `foo`
    bloopGenerate in Compile := None,
    // Bloop will not generate a target for the test configuration of `foo`
    bloopGenerate in Test := None,
  )
```

Next time you export, Bloop will automatically remove the old projects.

```bash
$ sbt bloopInstall
...
[warn] Removed stale /disk/foo/.bloop/foo.json
[warn] Removed stale /disk/foo/.bloop/foo-test.json
...
```

### Enable custom configurations

By default, `bloopInstall` exports projects for the standard `Compile`,
`Test` and `IntegrationTest` sbt configurations. If your build defines
additional configurations in a project, such as [your own sbt custom
configuration](https://www.scala-sbt.org/1.0/docs/offline/Testing.html#Custom+test+configuration),
you might want to export these configurations to Bloop projects too.

Exporting projects for additional sbt configuration requires changes in the
build definition in `build.sbt` (which in turn requires adding the
`sbt-bloop` plugin to your build):

```scala
import bloop.integrations.sbt.BloopDefaults

val MyCustom = config("custom-config") extend Test

// Example of a project configured with an additional `MyCustom` configuration
val foo = project
  .configs(MyCustom)
  .settings(
    // Scopes bloop configuration settings in `MyCustom`
    inConfig(MyCustom)(BloopDefaults.configSettings)
  )
```

When you reload your build, you can check that `bloopInstall` exports a new project called
`foo-custom-config.json`.

```
sbt> bloopInstall
[info] Loading global plugins from /Users/John/.sbt/1.0/plugins
(...)
[success] Generated '/disk/foo/.bloop/foo.json'.
[success] Generated '/disk/foo/.bloop/foo-test.json'.
[success] Generated '/disk/foo/.bloop/foo-custom-config.json'.
```

### Enable sbt project references 

Source dependencies are not well supported in sbt. Nonetheless, if you use them in your build and
you want to generate bloop configuration files for them too, add the following to your `build.sbt`:

```scala
// Note that this task has to be scoped globally
bloopAggregateSourceDependencies in Global := true
```

### Download dependencies sources

To enable source classifiers and download the sources of your binary dependencies, you can enabled
the following setting in your `build.sbt`:

```scala
bloopExportJarClassifiers in Global := Some(Set("sources"))
```

This option is required if you are using bloop with IDEs (e.g. Metals or IntelliJ) and expect
navigation to binary dependencies to work. After the option has been enabled, the bloop
configuration files of your projects should have one artifact per module with the "sources"
classifier.

```json
"resolution" : {
    "modules" : [
        {
            "organization" : "org.scala-lang",
            "name" : "scala-library",
            "version" : "2.12.7",
            "configurations" : "default",
            "artifacts" : [
                {
                    "name" : "scala-library",
                    "checksum" : {
                        "type" : "sha1",
                        "digest" : "c5a8eb12969e77db4c0dd785c104b59d226b8265"
                    },
                    "path" : "/disk/Caches/scala-library-2.12.7.jar"
                },
                {
                    "name" : "scala-library",
                    "classifier" : "javadoc",
                    "path" : "/disk/Caches/scala-library-2.12.7-javadoc.jar"
                },
                {
                    "name" : "scala-library",
                    "classifier" : "sources",
                    "path" : "/disk/Caches/scala-library-2.12.7-sources.jar"
                }
            ]
        }
    ]
}
```

### Export main class from sbt

If you want bloop to export `mainClass` from your build definition, define either of the following
settings in your `build.sbt`:

```scala
bloopMainClass in (Compile, run) := Some("foo.App")
```

The build plugin doesn't intentionally populate the main class directly from sbt's `mainClass`
because the execution of such a task may trigger compilation of projects in the build.

### Speeding up build export

`bloopInstall` typically completes in 15 seconds for a medium-sized projects once all dependencies
have already been downloaded. However, it can happen that running `bloopInstall` in your project is
slower than that. This long duration can usually be removed by making some changes in the build.

If you want to speed up this process, here's a list of things you can do.

Ensure that no compilation is triggered during `bloopInstall`. `bloopInstall` is intentionally
configured to skip project compilations to make the export process fast. If compilations are
triggered, then it means your build adds certain runtime dependencies in your build graph.

> For example, your build may be forcing the `publishLocal` of a project `a` whenever the classpath of
`b` is computed. Identify this kind of dependencies and prune them.

Another rule of thumb is to make sure that source and resource generators added to your build by
either you or sbt plugin are incremental and complete as soon as possible.

Lastly, make sure you keep a hot sbt session around as much time as possible. Running `bloopInstall`
a second time in the sbt session is *really* fast.

[sbt-configuration]: https://www.scala-sbt.org/1.x/docs/Multi-Project.html
[integration-test-conf]: https://www.scala-sbt.org/1.0/docs/offline/Testing.html#Integration+Tests
