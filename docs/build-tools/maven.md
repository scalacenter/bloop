---
id: maven
title: Maven
sidebar_label: Maven
---

## Getting Started

Configuring bloop with Maven projects speeds up developer workflows and gives you access to a modern Scala toolchain, despite Maven traditionally lacking good Scala suppport.

However, the Maven integration has a few issues that are not being actively
worked on. If you're a Maven user, please help out and introduce yourself in
our [Gitter channel](https://gitter.im/scalacenter/bloop) or comment on one
of the [open Maven
tickets](https://github.com/scalacenter/bloop/issues?q=is%3Aissue+is%3Aopen+sort%3Aupdated-desc+label%3Amaven).

<!-- start -->

## Requirements

- Your build uses [`scala-maven-plugin`](https://github.com/davidB/scala-maven-plugin/)

## Export your build

The Bloop plugin for Maven doesn't need to be installed, you can run
`bloopInstall`, the task to export your Maven builds to bloop, with the
following command.

```bash
$ mvn ch.epfl.scala:maven-bloop_2.10:@VERSION@:bloopInstall
```

Note that the following command uses the latest Bloop stable version
@VERSION@.

Here is the list of the latest Bloop stable and development versions. If you
want to be on the very latest at the cost of misbehaviors, replace the above
question with the development version.

```scala mdoc:releases
I am going to be replaced by the docs infrastructure.
```


To build with a single Scala project `foo` generates two configuration files:

```bash
$ mvn ch.epfl.scala:maven-bloop_2.10:@VERSION@:bloopInstall
(...)
Generated '/disk/foo/.bloop/foo.json'.
Generated '/disk/foo/.bloop/foo-test.json'.
```

where:
1. `foo` defines the main project; and,
1. `foo-test` defines the test project and depends on `foo`

`maven-bloop` generates two configuration files per Maven project. One
project for the main sources and another one for the tests. Bloop will skip
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

If you need help, you can always come over our [Gitter
channel](https://gitter.im/scalacenter/bloop).

## Well-known issues

### Detecting Java projects

At the moment, Java projects are not being detected. Head to [this
ticket](https://github.com/scalacenter/bloop/issues/519) for a reproducible
example.