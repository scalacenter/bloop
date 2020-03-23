---
id: gradle
title: Gradle
sidebar_label: Gradle
---

## Getting Started

Configuring bloop in your Gradle projects will speed up your Scala
development significantly. It is highly recommended to use Bloop in Gradle
because Gradle has a pretty long development cycle and it takes a long time
to do basic build operations such as compilation or running an application.
Learn how to get set up by following the instructions below.

<!-- start -->

## Requirements

- At least Gradle `v4.3`, latest (tested) supported version is `v6.1`

## Install the plugin

Here is a list of the latest Bloop stable and development versions.

```scala mdoc:releases
I am going to be replaced by the docs infrastructure.
```



Add bloop to your `build.gradle` with:

```gradle
buildscript {
  repositories {
    // Add here whatever repositories you're already using
    mavenCentral()
  }

  dependencies {
    classpath 'ch.epfl.scala:gradle-bloop_2.12:@VERSION@'
  }
}
```


Then, enable bloop in all your Gradle projects with:

```gradle
allprojects {
  apply plugin: 'bloop'
}
```

For a more advanced configuration, read the [Plugin Configuration](#plugin-configuration) below.

## Export your build

The gradle command `bloopInstall` exports your gradle build to bloop.

The gradle plugin generates a configuration file per [every source set in your build
definition](https://docs.gradle.org/current/userguide/scala_plugin.html#sec:scala_source_set_properties).
By default, there are two source sets: that of compile and that of test. Bloop will skip config file
generation for those projects that are not either Java or Scala.

```bash
$ ./gradlew bloopInstall
```

For example, a build with a single Scala project `foo` generates two configuration files by default:

```bash
$ ./gradlew bloopInstall
(...)
Generated '/disk/foo/.bloop/foo.json'.
Generated '/disk/foo/.bloop/foo-test.json'.
```

where:
1. `foo` comes from the compile source set; and,
1. `foo-test` comes from the test source set and depends on `foo`

If you have applied Bloop to all the projects in your build but some of your
projects can't be understood by Bloop (for example, they are not Java or
Scala projects), you will see the following log:

```
Ignoring 'bloopInstall' on non-Scala and non-Java project '$NON_JVM_PROJECT'
```

You can safely ignore such messsages.

## Verify installation and export

> Remember that the build server must be running in the background, as suggested by the [Setup
page](/setup).

Verify your installation by running `bloop projects` in the root of the gradle workspace directory.

```bash
$ bloop projects
foo
foo-test
```

If the results of `bloop projects` is empty, check that:

1. You are running the command-line invocation in the root base directory (e.g. `/disk/foo`).
1. The gradle build export process completed successfully.
1. The `.bloop/` configuration directory contains bloop configuration files.

> Debug the export with `./gradlew bloopInstall -Si`.

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

<!-- end -->

## Next steps after installation

Use an IDE such as [Metals](docs/ides/metals) or
[IntelliJ](docs/ides/intellij) to write code or play with the
[CLI](docs/cli/tutorial) if you want to explore what CLI options are
available.

If you need help, you can always come over our [Gitter
channel](https://gitter.im/scalacenter/bloop).


## Plugin Configuration

### Enable bloop plugin selectively

If your build only has a handful of Scala/Java projects and you don't want to
enable Bloop in all projects by default, you can selectively enable bloop with:

```groovy
apply plugin: 'bloop'
```

## Well-known issues

### Dealing with project name conflicts

Gradle project names are disambiguate by the path in which they are defined, even if the build
contains projects with the same simple name. In the bloop gradle plugin, simple project names have a
one-to-one equivalence with bloop project names. As a results, some name conflicts can occur when
there are two projects named `web` defined in `a/web` and `b/web`.

For the moment, there is not an official solution to this problem. If you want to help out fix it,
there is an open pull request proposing a solution that needs to be rebased and changed according to
feedback. If you feel like helping out, please comment in the ticket.
