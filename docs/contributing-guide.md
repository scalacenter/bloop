---
id: contributing-guide
title: Contributing to bloop
sidebar_label: Contributing Guide
---

## Requirements

1. Install Java 8 and sbt on your machine.
1. Follow the [Scala Native environment setup guide](https://scala-native.readthedocs.io/en/v0.3.9-docs/user/setup.html)
1. Check that you have Node v12.10.0 or newer by running `node -v`
1. Read and abide by the [Scala Code of Conduct](https://www.scala-lang.org/conduct/).

## Ensure you're in the right repo

Before getting started you'll want to make sure you're in the right place. Some
tools like [Mill](https://com-lihaoyi.github.io/mill/mill/Intro_to_Mill.html)
have their Bloop integration built-in, whereas others like
[sbt](https://www.scala-sbt.org/) are in here. If you're working on an
integration, make sure it's in this repo.

If you're making changes to the actual config structure, you'll want to make
those changes in the
[scalacenter/bloop-config](https://github.com/scalacenter/bloop-config) repo,
which will then need to be released and included in this build.

If you're looking for the Maven integration, then you'll want to make those
changes in
[scalacenter/bloop-maven-plugin](https://github.com/scalacenter/bloop-maven-plugin).

If you're looking to the Gradle integration, then you'll want to make those
changes in
[scalacenter/gradle-bloop](https://github.com/scalacenter/gradle-bloop).

## Project structure

Here's a list of the most important directories in the bloop repositories.

1. `backend` defines low-level compiler APIs wrapping Zinc APIs.
1. `frontend` defines the core of bloop, the task scheduling, the CLI and all the supported tasks: compilation, testing, running and linking.
1. `integrations` contains the sbt plugin to extract any sbt build to bloop.

When contributing to bloop, you will most likely need to modify code in the
above directories. The next directories define the rest of the project and
tooling infrastructure.

1. `bridges` contains Scala code written against Scala.js and Scala Native tooling APIs in a version-agnostic way.
1. `buildpress` is an application that given a list of `(project-name, vcs-uri)` will export a build to bloop.
1. `docs` and `docs-gen` define our docs infrastructure.
1. `benchmark-bridge` and `benchmarks` define our compiler benchmark infrastructure.
1. `bloop-rifle` is a module to start a bloop server and establish a
bsp/cli connection to it.
1. `website` contains the [Docusaurus](https://docusaurus.io/) code of our website.

## Set the repository up

1. Clone the [GitHub repository](https://github.com/scalacenter/bloop)
1. Change directory to bloop's base directory and run `git submodule update --init`
1. Run the sbt shell in the base directory.

If the sbt shell did not fail, you already have a working bloop build in your
machine.

To hack on the bloop repository you can use either
[Metals](https://scalameta.org/metals/) or
[IntelliJ](https://www.jetbrains.com/idea/). Open the project and import the
build in order to get started.

## Learn the developer workflow

The recommended way to build and hack on bloop is by using bloop. Run
`bloopInstall` in your sbt shell if you are not using Metals to export the build to the
`.bloop/` configuration directory.

### Common commands

Once the build is exported, here are a few commands to test your changes.

1. `bloop test backend` runs unit tests for the `backend/` project.
1. `bloop test frontend` runs unit tests for most of the build server. This is the most common command to run.
1. `bloop test frontend -o bloop.CompileSpec` will test only the compilation spec.
1. `bloop test frontend -o bloop.DeduplicationSpec -- "bloop.DeduplicationSpec.three concurrent clients deduplicate compilation"` will test only that particular test case in the `DeduplicationSpec` test suite.

### Write commit messages

Write concise commit messages, with a capital letter at the beginning of the
commit message and with a potential description of what your change does so
that future code wanderers can understand what your contribution is.

**Tip**: If you're only making changes to the docs, add `[DOCS]` to the commit
message so that Bloop does not run tests for your commit.

## Install a SNAPSHOT release

When a PR is merged in `scalacenter/bloop`, the CI cuts a new release
available to any Bloop user. The version number of the latest SNAPSHOT
release can be found in the version table available in the [Installation
guide](/bloop/setup). It has a stable version number to
make dependencies reproducible.

### Depend on SNAPSHOT releases

The table in [the setup page](/bloop/setup) shows the latest stable and
SNAPSHOT versions available. Pick either.

Then take a look at the list of published artifacts in [this Sonatype
link](https://search.maven.org/search?q=g:ch.epfl.scala%20a:bloop*) and add
them to your project. For example, to depend on the latest SNAPSHOT of bloop
launcher in sbt, add the following to your project:

```scala
libraryDependencies += "ch.epfl.scala" % "bloop-rifle" % "$VERSION"
```

### Install a SNAPSHOT release in your machine

If you want to install a SNAPSHOT bloop version in your machine because, for
example, you want to use the CLI or integrate it with a local build tool and
editor, follow these steps:

1. Checkout the commit in main you want to use.
1. Run `sbt install`. After the command runs, you have:
    * Built all modules in the bloop repository.
    * Generated installation scripts and package manager formulas.

The logs contain the paths of every installation resource that has been
generated by `install`.

#### Homebrew

If you have installed bloop via `brew`, look for the path of the Homebrew
formula (ending in `.rb`) in the installation logs and run `brew upgrade
--build-from-source $LOCAL_HOMEBREW_FORMULA`.

Then, **restart the bloop server** to pick up the new installed version.

The command to run depends on how you have run the bloop server, the [Build
Server reference](server.md) explains the available startup mechanisms. For
example, if you're a macOS user and use `brew services`, restart the server
with `brew services restart bloop`.

#### Scoop

If you have installed bloop via `scoop`, look for the path of the Scoop
formula in the installation logs and upgrade bloop with the `scoop` CLI.

Then, **restart the bloop server** to pick up the new installed version.

The command to run depends on how you have run the bloop server, the [Build
Server reference](server.md) explains the available startup mechanisms.

#### Scala CLI

Scala CLI also uses Bloop to compile Scala code. To run it with a specific
version of Bloop you can use the bloop subcommand, which requires the
`--power` flag to run.

```
scala-cli --power bloop --bloop-version 2.0.6-85-43d49584-SNAPSHOT about
```

#### Metals

To use the new version of bloop in Metals, you need to update the 
`metals.bloopVersion` and agree to restart build server when prompted by Metals.

#### Generic installation (most common)

If you installed Bloop via coursier you can use the same option to install 
snapshot or locally released versions.

```bash
cs launch -r sonatype:snapshots ch.epfl.scala:bloop-cli_2.13:2.0.6-85-43d49584-SNAPSHOT  -M bloop.cli.Bloop -- about
```

You can replace the above snapshot version with the one you want to install.

### Verify the installation of a SNAPSHOT release

Then, restart the bloop server to pick up the new local version. Verify the
local installation by comparing the SNAPSHOT version you wanted to install
with the version of the running server.

```bash
➜ bloop v2.0.0

Using Scala v2.12.19 and Zinc v1.10.1
Running on Java JDK v21 (/usr/lib/jvm/openjdk21)
  -> Supports debugging user code, Java Debug Interface (JDI) is available.
Maintained by the Scala Center and the community.

```

Note the bloop version number in the first line of the above logs.

## Updating documentation

### Prerequisites
1. `yarn` is installed - Instructions [here](https://classic.yarnpkg.com/lang/en/docs/install/)
2. `docusaurus` is installed in `yarn` - run `yarn add docusaurus`

### Running
The workflow to update the documentation is the following:

1. Run the docs generation via `bloop run docs -w` or `~docs/run` if you're using the sbt shell.
1. Run `yarn start` inside the `website/` directory. Yarn will start a local web server with the current state of the docs.
1. Locate the Markdown file you want to edit
1. Make a change in the Markdown file. The first command will automatically rebuild the docs and the second one will show it in the browser.

If the previous procedure doesn't show a change because, for example, you
modified the name of a sidebar or the title of a documentation page, type
<kbd>CTRL</kbd>+<kbd>C</kbd> on the `yarn start` process and run it again.
Docusaurus doesn't incrementally rebuild changes to the Markdown page
indices.

## Releasing

- Draft the release notes:

  - You might use the `./bin/release_notes.sc` script to generate merged PRs list
    between the two last release tags. It can be run using ammonite:

  ```
  cs install ammonite
  amm ./bin/merged_prs.sc <tag1> <tag2> "<github_api_token>" > notes/<tag2>.md
  ```

  It will need a basic github API token to run, which may be specified via
  an environment variable, `GITHUB_TOKEN`, or via the last argument.

  The release notes must end up in the `notes` directory so that it can be later
  picked up by the bot.

  - Create a PR with the release notes and wait for approvals
  - Merge the PR!

- Release:
  - Sync changes with the recent main
  - Change the tag to point to the most recent commit with the release notes.
  - Make sure that all the jobs are finished! This is important as some jobs are
    delayed and if we push too early not all the neccessary steps will succeed.
  - Push the new tag and wait for the release
- Announce the release after the release notes are published in the most recent
  release.


# Best effort compilation pipeline

As of writing this part of the doc this is an experimental set of settings implemented
in the Scala 3 compiler (starting with 3.5.x). They allow the compiler to return artifacts
even when the compilation fails (returning `.betasty` files instead of `.class` and `.tasty`).
It also at this point does not support incremental compilation. This all requires special
handling from the build tool, mostly located in `Compiler.scala`, `CompileTask.scala`
and `CompileGraph.scala`:
- We save best effort artifacts separately, and allow dependent projects to compile using 
that, even when the compilation has failed. If the project compiles we discard the best effort
artifacts.
- First, we try compiling partially (only the modified files), expecting regular successful compilation
- If that at some point fails, we discard the immediate results and recompile the whole module
expecting .betasty files. We do not ever move them to a readOnly directory. That readOnly directory
is also not used in dependent compilations.
- We do not try to recompile if we know we are compiling the whole module to begin with (e.g. because we
are depending on .betasty from different project, or because this is the first compilation and we
do not have any previous incremental compilation analysis).
- If we try to recompile a module that we previously compiled for .betasty, we once again, try to
recompile it 2 times - once incrementally expecting success (recompiling all files changed since
the last successful compilation, as dictated by the incremental compilation analysis) and then
recompile all - this works out to be faster than discarding the last successful result and jumping
between full successful recompilation and full best effort recompilation.
