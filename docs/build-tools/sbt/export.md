## Install the plugin

Install bloop in `project/plugins.sbt`:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "1.1.0")
```

And start up or reload your sbt shell to add the plugin to your working build.

## Generate configuration files

The sbt command `bloopInstall` exports your sbt build to bloop.

In bloop, an sbt project is repesented as a pair of `(sbt project, sbt configuration)` and it's
written to a configuration directory. The default location of this directory in your workspace is
`.bloop/` (you may want to add `.bloop/` to your `.gitignore` file).

For example, a build with a single project `foo` generates two configuration files by
default (one per [sbt configuration][sbt-configuration]):

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

## Next steps

Start using [Bloop with the CLI](docs/usage) or [configure it with the supported
IDEs](docs/ides/overview).

If you want to configure the installation step or learn more about the integration, visit the
[sbt documentation page](docs/build-tools/sbt).
