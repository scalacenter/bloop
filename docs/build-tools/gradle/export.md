Gradle 4.8.x uses a three years old version of Zinc (Scala's incremental compiler) which [misses out
on a lot of improvements with regards to performance and correctness in
1.x](https://www.scala-lang.org/blog/2017/11/03/zinc-blog-1.0.html). Installing bloop makes a big
difference in Gradle builds.

## Install the plugin

Install bloop in `build.gradle`:

```groovy
buildscript {
  repositories {
    // Add here whatever repositories you're already using
    mavenCentral()
  }

  dependencies {
    classpath 'ch.epfl.scala:gradle-bloop_2.11:@VERSION@'
  }
}
```

Then, apply the plugin to the build projects you use to use from bloop. You can do it for all the
projects in the build (the most common use case).

```groovy
allprojects {
  apply plugin 'bloop'
}
```

Or, selectively per project:

```groovy
apply plugin 'bloop'
```

## Export the build

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

> If you want to configure the installation step, head to [the Bloop Maven documentation
page](build-tools/maven.md).

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

## Next steps

Start using [Bloop with the CLI](docs/usage) or [configure it with the supported IDEs](docs/ides/overview).

If you want to configure the installation step or learn more about the integration, visit the
[Gradle documentation page](docs/build-tools/gradle).
