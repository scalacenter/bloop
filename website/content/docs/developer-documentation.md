+++
description = "A contributors guide for Bloop."
title = "Developer documentation"
date = "2018-02-20T13:45:00+01:00"
draft = false
weight = 10
bref = "Learn how to contribute to bloop in a nutshell."
toc = true
+++

This is all the documentation that is relevant for the developers of Bloop and
anyone who would like to contribute to Bloop.

## Improving the docs

Our documentation infrastructure uses the [Hugo](http://gohugo.io/) static site
generator, together with a custom version of the [Kube](kube.elemnts.org/)
theme. The docs live in the `website/` folder in our repository.

If you want to make changes to the theme or how the documents are organised (think html or css), head to Scala Center's fork of [Kube](https://github.com/scalacenter/kube) to make the changes and make a pull request.

If you want to make changes to the contents of the docs, you can click on the
"Edit on GitHub" button in every documentation page or, alternatively, make the
change in your local copy of Bloop's git repository. All documentation pages
live in `website/content/docs/`.

We recommend that whenever you make changes to the docs you add `[DOCS]` to the
commit message to tell the CI server to skip tests and speed up the merge
process.

## Building Bloop locally

The following sequence of commands is sufficient to build Bloop, publish it
locally on your machine and generate the binaries that you can immediately run:

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

## Running our test suite

Bloop has a rich integration test suite that requires build definitions to
exist to be able to compile a project. These build definitions are
machine-specific and they need to be created on the fly.

Before running the test suite, it is necessary to ensure that these build
definitions exist and are up-to-date with the following sbt task:

```
> frontend/integrationSetUpBloop
```

This will clone several open source projects and use the sbt-bloop plugin to
generate the build definitions. Then, run tests with:

```
> frontend/test
```

## Running our community build

A community build is a collection of builds for which we test against on every
major change in bloop. It helps us keep track of potential regressions in both
correctness and performance.

Right now, our community build counts with several sbt 0.13 and 1.0 builds. As
running the community, build takes a while, it's run only before releases and
on major breaking changes.

### Running the community build on a pull request

The community build on every pull request with the label `community-build`.

### Running the community build locally

Run the community build locally by setting the `RUN_COMMUNITY_BUILD`
environment variable before running the tests locally.

```
$ export RUN_COMMUNITY_BUILD=true
$ sbt "frontend/testOnly bloop.tasks.IntegrationTestSuite"
```

## Running our benchmarks

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

### Running the benchmarks on a pull request

Schedule the benchmarks in a given pull request by adding a comment that
contains "test performance please". [bloopoid](https://github.com/bloopoid)
will then kick in and confirm your request.

### Running the benchmarks locally

`bin/run-benchmarks.sh` builds a specified version of Bloop (via CLI) and runs
the benchmarks for it. Run it to execute the benchmark suite locally, but keep
in mind that you must make sure your machine is stable and has been tweaked to
get stable results.
