## Install the plugin

Install bloop in `build.sc`:

```scala
import $ivy.`ch.epfl.scala::mill-bloop:@VERSION@`
```

### Requirements

* `mill` >= 0.3.5.

> bloop makes a best effort to support always the latest mill version. The current plugin may not
work for older and newer versions due to breaking binary changes in mill's public API.

## Generate configuration files

The mill command `bloopInstall` exports your mill build to bloop.

The mill plugin generates a configuration file per every compile and test sources in your build
definition. For example, a build with a single Scala project `foo` generates two configuration files
by default:

```bash
$ mill bloop.integrations.mill.Bloop/install
(...)
info Generated '/disk/foo/.bloop/foo.json'.
info Generated '/disk/foo/.bloop/foo-test.json'.
```

where:
1. `foo` comes from the compile source set; and,
1. `foo-test` comes from the test source set and depends on `foo`

> If you want to configure the installation step, head to [the Bloop mill documentation
page](build-tools/mill.md).

## Verify installation and export

> Remember that the build server must be running in the background, as suggested by the [Setup
page](/setup).

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

After verifying the export, you can continue using Bloop's command-line application or any build
client integrating with Bloop, such as [Metals](https://scalameta.org/metals/).

## Next steps

Start using [Bloop with the CLI](docs/usage) or [configure it with the supported
IDEs](docs/ides/overview).

If you want to configure the installation step or learn more about the integration, visit the
[Mill documentation page](docs/build-tools/mill).
