---
id: mill
title: Mill
sidebar_label: Mill
---

## Getting Started

You can use Mill together with Bloop to speed up your development workflow and better integrate your build with your IDE.

Mill has built-in support for Bloop via `mill-contrib`, so follow the instructions below to install the `mill-bloop` module.

<!-- start -->

## Requirements

- Your mill version needs to be >= `0.4.0-2-4dbbce`.

## Install the plugin

Install bloop in `build.sc` by replacing `$millVersion` in the following
command:

```scala
import $ivy.`com.lihaoyi::mill-contrib-bloop:$millVersion`
```

## Export your build

The mill command `mill mill.contrib.Bloop/install` exports your mill build to bloop.

The mill plugin generates a configuration file per every compile and test
sources in your build definition. For example, a build with a single Scala
project `foo` generates two configuration files by default:

```bash
$ mill mill.contrib.Bloop/install
(...)
info Generated '/disk/foo/.bloop/foo.json'.
info Generated '/disk/foo/.bloop/foo-test.json'.
```

where:
  
1. `foo` comes from the compile source set; and,
1. `foo-test` comes from the test source set and depends on `foo`

## Verify installation and export

> If you haven't installed bloop and its CLI yet, [follow these instructions](/setup) before proceeding.

Verify your installation by running `bloop projects` in the root of the mill workspace directory.

```bash
$ bloop projects
foo
foo-test
```

If the results of `bloop projects` is empty, check that:

1. You are running the command-line invocation in the root base directory (e.g. `/disk/foo`).
1. The gradle build export process completed successfully.
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

After verifying the export, you can continue using Bloop's command-line
application or any build client integrating with Bloop, such as
[Metals](https://scalameta.org/metals/).

<!-- end -->

## Next steps after installation

Use an IDE such as [Metals](docs/ides/metals) or
[IntelliJ](docs/ides/intellij) to write code or play with the
[CLI](docs/cli/tutorial) if you want to explore what CLI options are
available.

If you need help, you can always come over our [Gitter
channel](https://gitter.im/scalacenter/bloop).