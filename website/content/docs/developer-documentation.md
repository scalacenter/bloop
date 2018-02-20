+++
description = "Documentation for the developer and contributors to Bloop"
title = "Developer documentation"
date = "2018-02-20T13:45:00+01:00"
draft = false
weight = 10
bref = "Documentation for developer, explaining how to contribute to Bloop and use a development version of Bloop"
toc = true
+++

This is all the documentation that is relevant for the developers of Bloop and anyone who would
like to contribute to Bloop.

### Building Bloop locally

The following sequence of commands is sufficient to build Bloop, publish it locally on your machine
and generate the binaries that you can immediately run:

```sh
$ git clone --recursive https://github.com/scalacenter/bloop.git
$ cd bloop
$ sbt
> install
> frontend/version # copy this version number
$ cd nailgun
$ git rev-parse HEAD # copy this commit SHA
$ cd ..
# paste the version number and SHA obtained above in the following command:
$ bin/install.py --dest $HOME/.bloop --nailgun <nailgun-commit-sha> --version <version>
```

### Running our test suite

Bloop is a complicated project to test. It requires build definitions to exist to be able to
compile a project, and because of the machine-specific nature of these build definitions, they
need to be created on the fly.

Before running all of our test suite, it is therefore necessary to ensure that these build
definitions exist and are up-to-date. Fortunately, we provide a command in sbt to perform this
task:

```
> frontend/integrationSetUpBloop
```

This will clone several open source projects and use sbt-bloop to generate the build definitions.
Our tests can then be run:

```
> frontend/test
```

### Running our community build

To ensure that Bloop is able to compile all kinds of projects, we have a corpus of open source
projects that can be directly compiled with Bloop within our test suite. Because these tests take
a long time to run, they are not run by default.

#### Running the community build on a pull request

To enable the community build to run on a pull request, it is necessary to add the
`community-build` label to the pull request.

#### Running the community build locally

The `RUN_COMMUNITY_BUILD` environment variable must be set before starting the tests:

```
$ export RUN_COMMUNITY_BUILD=true
$ sbt "frontend/testOnly bloop.tasks.IntegrationTestSuite"
```

### Running our benchmarks

Bloop features several benchmark that measure how fast Bloop is to perform several operations
(loading projects for instance), as well as other benchmark that compare the compilation speed of
Bloop against scalac and sbt. The benchmarks run on a dedicated machine located at EPFL.

#### Overview

Our whole benchmark infrastructure is composed of several pieces:

 - We reuse and slightly modify the infrastructure of
   [scala/compiler-benchmark](https://github.com/scala/compiler-benchmark). Our fork can be
   found at [scalacenter/compiler-benchmark](https://github.com/scalacenter/compiler-benchmark).
 - The benchmark-listened, which listens to comments on PR saying `test performance please`. This
   is adapted from Dotty's [liufengyun/bench](https://github.com/liufengyun/bench]). Our fork can
   be found at
   [scalacenter/bloop-benchmark-listener](https://github.com/scalacenter/bloop-benchmark-listener).
 - InfluxDB and Grafana instances running on a machine at EPFL. This machine collects the
   benchmark results and displays them on nice graphs. At this time, it is only accessible from
   within EPFL's network.

#### Running the benchmarks on a pull request

To schedule the benchmarks for a given pull request, send the following comment on the pull
request and [bloopoid](https://github.com/bloopoid) will schedule the benchmarks for you:

> test performance please

#### Running the benchmarks locally

Bloop's repository contains a shell scripts that takes care of building the specified version of
Bloop and running all the benchmarks. This script can be found in `bin/run-benchmarks.sh`.

