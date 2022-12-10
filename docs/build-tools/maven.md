---
id: maven
title: Maven
sidebar_label: Maven
---

## Getting Started

Configuring Bloop with Maven projects speeds up developer workflows and gives
you access to a modern Scala toolchain, despite Maven traditionally lacking good
Scala support.

However, the Maven integration has a few issues that are not actively being
worked on. If you're a Maven user, please help out and introduce yourself in our
[Bloop Discord channel](https://discord.gg/KWF9zMhJWS) or comment on one of the
[open issues](https://github.com/scalacenter/bloop-maven-plugin/issues) in its
own repo at
[`scalacenter/bloop-maven-plugin`](https://github.com/scalacenter/bloop-maven-plugin).


<!-- start -->

## Requirements

- Your build uses [`scala-maven-plugin`](https://github.com/davidB/scala-maven-plugin/)

## Export your build

The Bloop plugin for Maven doesn't need to be installed, you can run
`bloopInstall`, the task to export your Maven builds to bloop, with the
following command.

```bash
$ mvn ch.epfl.scala:bloop-maven-plugin:@BLOOP_MAVEN_VERSION@:bloopInstall
```

In some cases, when you are using generated sources in you project
you might need to run:

```bash
$ mvn generate-sources ch.epfl.scala:bloop-maven-plugin:@BLOOP_MAVEN_VERSION@:bloopInstall
```

**Note**: if you've used this command before you'll have noticed the artifact
name has changed. In the past you would have used `maven-bloop_2.13`, however as
of the 2.x series the artifact name has changed to `bloop-maven-plugin`.

To build with a single Scala project `foo` generates two configuration files:

```bash
$ mvn ch.epfl.scala:bloop-maven-plugin:@BLOOP_MAVEN_VERSION@:bloopInstall
(...)
Generated '/disk/foo/.bloop/foo.json'.
Generated '/disk/foo/.bloop/foo-test.json'.
```

where:
1. `foo` defines the main project; and,
1. `foo-test` defines the test project and depends on `foo`

The `bloop-maven-plugin` generates two configuration files per Maven project.
One project for the main sources and another one for the tests. Bloop will skip
config file generation for those projects that are not either Java or Scala.

## Verify installation and export

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

<!-- end -->

## Next steps after installation

Use an IDE such as [Metals](docs/ides/metals) or
[IntelliJ](docs/ides/intellij) to write code or play with the
[CLI](docs/cli/tutorial) if you want to explore what CLI options are
available.

If you need help, you can always come over our [Discord
channel](https://discord.gg/KWF9zMhJWS).

## Well-known issues

### Detecting Java projects

At the moment, Java projects are only partially supported in the Maven export.
If you experience issues with this please open an
[issue](https://github.com/scalacenter/bloop-maven-plugin).
