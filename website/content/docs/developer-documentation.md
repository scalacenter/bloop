+++
description = "A contributors guide for Bloop."
title = "Developer documentation"
date = "2018-02-20T13:45:00+01:00"
draft = false
weight = 12
bref = "Learn how to contribute to bloop in a nutshell."
toc = true
+++

This is all the documentation that is relevant for the developers of Bloop and
anyone who would like to contribute to Bloop.

## Improving the docs

Our documentation infrastructure uses the [Hugo](http://gohugo.io/) static site
generator, together with a custom version of the [Kube](https://kube.elemnts.org/)
theme. The docs live in the `website/` folder in our repository.

If you want to make changes to the theme or how the documents are organised (think html or css), head to Scala Center's fork of [Kube](https://github.com/scalacenter/kube) to make the changes and make a pull request.

If you want to make changes to the contents of the docs, you can click on the
"Edit on GitHub" button in every documentation page or, alternatively, make the
change in your local copy of Bloop's git repository. All documentation pages
live in `website/content/docs/`.

We recommend that whenever you make changes to the docs you add `[DOCS]` to the
commit message to tell the CI server to skip tests and speed up the merge
process.

### Changing command syntax

If you change command syntax, please also update our
[commands reference]({{< relref "commands-reference.md" >}}) by running:

```sh
$ bloop run -p frontend -m bloop.util.CommandsDocGenerator
```

and copying the output to `website/content/docs/commands-reference.md` (keeping old header intact).

## Opening up our sbt build

Bloop builds with sbt and uses git submodules. To avoid getting cryptic errors like
"No project 'compilation' in 'file:///Users/ashwinraja/Development/bloop/benchmark-bridge/'"
you need to clone the repository and initialize the submodules *before* you run sbt.

If you haven't yet cloned bloop, run the following commands:

```sh
$ git clone --recursive https://github.com/scalacenter/bloop.git
$ cd bloop
$ sbt
```

If you forgot to initialize git submodules, run `git submodule update --init` in the bloop
repository.

## Building Bloop locally

The following sequence of commands is sufficient to build Bloop, publish it
locally on your machine and generate the binaries that you can immediately run:

```sh
$ git clone --recursive https://github.com/scalacenter/bloop.git
$ cd bloop
$ sbt
> install
> frontend/version # copy this version number as <version>
$ cd nailgun
$ git rev-parse HEAD # copy this commit SHA as <nailgun-commit-sha>
$ cd ..
$ grep 'val coursierVersion' project/Dependencies.scala # copy this version number as <coursier-version>
# paste the version numbers and SHA obtained above in the following command:
$ bin/install.py --dest $HOME/.bloop --nailgun <nailgun-commit-sha> --coursier <coursier-version> --version <version>
```

### Installing bloop locally in OSX (brew)

If you use brew to install and manage Bloop's different versions and want to
try a new local version, the `install` sbt command will also publish a local
ruby formula you can use with:

`brew upgrade --build-from-source ${PATH_TO_LOCAL_HOMEBREW_FORMULA}`

Assuming the new installed version is an upgrade.

## Running our test suite

Bloop has a rich integration test suite that requires build definitions to
exist to be able to compile a project. These build definitions are
machine-specific and they need to be created on the fly.

Before running the test suite, it is necessary to ensure that these build
definitions exist and are up-to-date with the following sbt task:

```
> frontend/integrationSetUpBloop
```

This will clone Bloop's community build on your sbt staging directory and will
generate the corresponding build definitions for them. This process can take
a while (and look like it's stuck), so please be patient and let sbt clone all
the projects. When the process is over, you can run the tests with:

```
> frontend/test
```

## Running the community build

A community build is a collection of builds we test against on every
significant change in bloop. It helps us keep track of potential regressions in
both correctness and performance.

Right now, our community build counts with many important sbt 0.13 and 1.0
projects in the Scala community. As running the community build takes a while,
it's run only before releases and on major breaking changes.

### Running the community build on a pull request

The community build on every pull request with the label `community-build`.

### Running the community build locally

Run the community build locally by setting the `RUN_COMMUNITY_BUILD`
environment variable before running the tests locally.

```
$ export RUN_COMMUNITY_BUILD=true
$ sbt "frontend/testOnly bloop.tasks.IntegrationTestSuite"
```

## Running benchmarks

Bloop features a benchmark suite to measure how fast Bloop is to perform
several operations (loading projects for instance), as well as other benchmark
that compare the compilation speed of Bloop against scalac and sbt. The
benchmarks run on a dedicated machine located at EPFL.

### Overview

Our benchmark infrastructure is composed of several pieces:

 - [scala/compiler-benchmark](https://github.com/scala/compiler-benchmark). Our fork can be
   found at [scalacenter/compiler-benchmark](https://github.com/scalacenter/compiler-benchmark).
 - The benchmark-listened, which listens to comments on PR saying `test performance please`. This
   is adapted from Dotty's [liufengyun/bench](https://github.com/liufengyun/bench]). Our fork can
   be found at
   [scalacenter/bloop-benchmark-listener](https://github.com/scalacenter/bloop-benchmark-listener).
 - InfluxDB and Grafana instances running on a machine at EPFL. This machine collects the
   benchmark results and displays them on graphs. At this time, it is only accessible from
   within EPFL's network.

### Running benchmarks on a pull request

Schedule the benchmarks in a given pull request by adding a comment that
contains "test performance please". [bloopoid](https://github.com/bloopoid)
will then kick in and confirm your request.

### Running benchmarks locally

`bin/run-benchmarks.sh` builds a specified version of Bloop (via CLI) and runs
the benchmarks for it. Run it to execute the benchmark suite locally, but keep
in mind that you must make sure your machine is stable and has been tweaked to
get stable results.

## Debugging

If you're trying to catch a bug, it might be convenient to attach a debugger
to Bloop instead of creating your own tests and `println`ing.

In order to attach a debugger you need to run Bloop with some additional JVM
options to enable debugging in the first place. Here we use the standard JDWP
(Java Debug Wire Protocol) agent.

After running the commands shown below, attach your favourite debugger to port
`5005`. The JVM will suspend startup until the debugger is attached --- change
`suspend` to `n` if you don't want this behaviour.

### Debugging installed version of bloop

If you want to debug your installed bloop server, first kill the server. You
can do this with, for example, `systemctl stop --user bloop` or a similar
incantation. The command to run depends on your OS and how you installed and
started the server.

The server is now stopped. You can run a debuggable Bloop server with either of these options:

#### Using `bloop server`

```bash
bloop server -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005
Listening for transport dt_socket at address: 5005
```

#### Using `JAVA_TOOL_OPTIONS`

```bash
JAVA_TOOL_OPTIONS='-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005' bloop server
Picked up JAVA_TOOL_OPTIONS: -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005
Listening for transport dt_socket at address: 5005
```

### Running Bloop server from sbt

Run the bloop server directly from sbt:

```sh
$ sbt
> frontend/runMain bloop.Server
```

Kill the bloop server with <kbd>Ctrl</kbd><kbd>C</kbd>.

#### Attaching debugger in local bloop

This is mostly useful for Bloop developers that have a local copy of bloop
they want to debug. You can debug bloop with three tools: `bloop` (if you use
it to develop `bloop`), `sbt` or `ensime`.

##### bloop

```bash
bloop run frontend -- -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005
Listening for transport dt_socket at address: 5005
```

##### sbt

```sh
$ sbt
> set javaOptions in (frontend, run) += "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
> frontend/runMain bloop.Server
Listening for transport dt_socket at address: 5005
```

##### Ensime

```sh
$ sbt
> frontend/ensimeRunDebug bloop.Server
Listening for transport dt_socket at address: 5005
```
