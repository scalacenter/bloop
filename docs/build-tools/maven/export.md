> Despite having several happy Maven users, the Maven integration has a few issues that are not
being actively worked on. If you're a Maven user and want to help out, say hi in our [Gitter
channel](https://gitter.im/scalacenter/bloop) and eyeball [open
issues](https://github.com/scalacenter/bloop/issues?q=is%3Aissue+is%3Aopen+sort%3Aupdated-desc+label%3Amaven).

## Generate configuration files

The Maven command `bloopInstall` exports your Maven builds to bloop.

The Maven plugin requires
[`davidB/scala-maven-plugin`](https://github.com/davidB/scala-maven-plugin/) because it extracts
Scala settings from its plugin-specific configuration. This plugin is the most popular Scala
integration for Maven.

`maven-bloop` generates two configuration files per Maven project. One project for the main sources
and another one for the tests. Bloop will skip config file generation for those projects that are
not either Java or Scala.

```bash
$ mvn ch.epfl.scala:maven-bloop_2.10:@VERSION@:bloopInstall
```

For example, a build with a single Scala project `foo` generates two configuration files:

```bash
$ mvn ch.epfl.scala:maven-bloop_2.10:@VERSION@:bloopInstall
(...)
Generated '/disk/foo/.bloop/foo.json'.
Generated '/disk/foo/.bloop/foo-test.json'.
```

where:
1. `foo` defines the main project; and,
1. `foo-test` defines the test project and depends on `foo`

## Verify installation and export

> Remember that the build server must be running in the background, as suggested by the [Setup
page](/setup).

Verify your installation by running `bloop projects` in the root of the Maven workspace directory.

```bash
$ bloop projects
foo
foo-test
```

If the results of `bloop projects` is empty, check that:

1. You are running the command-line invocation in the root base directory (e.g. `/disk/foo`).
1. The Maven build export process completed successfully.
1. The `.bloop/` configuration directory contains bloop configuration files.

If you suspect bloop is loading the configuration files from somewhere else, run `--verbose`:

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

After verifying the export, you can continue using Bloop's command-line application or any build
client integrating with Bloop, such as [Metals](https://scalameta.org/metals/).

## Next steps

Start using [Bloop with the CLI](docs/usage) or [configure it with the supported
IDEs](docs/ides/overview).

If you want to configure the installation step or learn more about the integration, visit the
[Maven documentation page](docs/build-tools/maven).
