---
id: what-is-bloop
title: What is Bloop
sidebar_label: What is Bloop
---

Bloop is a build server and CLI tool for the [Scala programming language](https://scala-lang.org/) developed by [the Scala Center][scalacenter]. Bloop has **three main goals**:

1. Compiles, tests and runs Scala code as fast as possible
1. Encapsulates these capabilities in an independent, long-lived process reusable by multiple clients
1. Integrates easily with build tools, command-line applications, editors and custom tooling

## Features

1. Compiles a vast array of Java and Scala (2.10.x, 2.11.x, 2.12.x and 3.x) versions
1. Runs and tests on the Java, [Scala.js](https://www.scala-js.org/) and [Scala Native](https://github.com/scala-native/scala-native) runtimes
1. Integrates with common build tools in the Scala ecosystem (sbt, Gradle, mill, Maven, etc)
1. Implements server-like capabilities such as concurrent build execution,
   caching compilations across clients and build client isolation to avoid conflicts in a shared, stateful file system.

## Limitations

1. There can only be one bloop server instance running per machine.
1. There is currently no support for remote compilation, see [this ticket](https://github.com/scalacenter/bloop/issues/673)

## Getting started

Just as the web server powering this website responds to your request to load
it—which in turn delivers static resources, generates html pages, caches
them and handles requests in parallel—, bloop runs in the background of your
machine for long periods of time to respond to any compile, test or run
client request.

Before using bloop, let's get familiar with the essentials and
responsibilities of a build server.

### Usage of the build server

Clients can *concurrently* ask for build requests in two ways:

1. via the [Nailgun server protocol](https://github.com/facebook/nailgun),
    used by the built-in bloop command-line application
1. via the [Build Server Protocol (BSP)](https://github.com/scalacenter/bsp)
   (**recommended**), used by clients such as
   [Metals](https://github.com/scalameta/metals) or
   [IntelliJ](https://www.jetbrains.com/idea/)

### Guarantees to build clients

At the same time, these clients have the guarantee that their actions will not
conflict with those of other concurrent clients being served by the same
server in the same build.

For example, if [Metals](https://github.com/scalameta/metals) is compiling
your project via Bloop and you spawn a CLI command such as test at the same
time, Bloop guarantees that:

1. The server heavily caches compilations for the same inputs (aka *compile deduplication*)
   > If inputs haven't changed between the requests, only the first client
   > request will trigger a compilation. The compilation of the second client
   > will be deduplicated based on the compilation side effects recorded by the
   >build server, so only one compilation will happen.
1. Different compilation requests in the same build can run concurrently (aka
   *compile isolation*)
   > If inputs have changed between requests, Bloop will compile the changed
   > projects concurrently, avoiding shared state and conflicts with ongoing
   > compilations.
1. The outputs produced by both requests are independent in the file system.
   > The compilation products will be stored in independent target
   > directories only owned by the client. This independence is essential to
   > allow clients to independently run any build action without altering task
   > executions.

These properties are **key to understand Bloop's goal as a build server**.
Bloop is trying to model these actions as *pure functions*, just like your
web server does, managing any internal state as best as possible.

### Use or integrate with bloop

That's it, this is all you need to know to get started using Bloop!

* To **use** bloop with your current build tool:
  * Follow the [Installation guide](/setup) to install and integrate with your build.
  * Read the [Quickstart](docs/usage) page to get you acquainted with Bloop.
* To **integrate** with bloop, follow the [Integration Guide](integration.md).

## Design Principles

The lack of clear design principles in previous Scala tools has hindered
progress in the tooling community, complicated maintenance and worsened the
Scala user experience with, for example, slower compiles.

Bloop addresses such problems with three philosophical principles that shape
its design and improve on the status quo by building the foundations of a
flexible and reusable Scala toolchain for everyone.

### Implement Once, Optimize Once, Use Everywhere

Every developer tool supporting Scala needs to compile, test and run Scala
code. These are basic features that both old and new tools alike require,
from build tools to IDEs/editors, in-house tooling and custom scripts.

Implementing custom client integrations is a tedious task for the close-knit
community of Scala tooling contributors. In practice, contributors repeat the
same learning process, duplicate the integration logic and its optimizations,
have trouble benchmarking compilation performance consistently across all
clients and lack ways to assess the quality of the Scala user experience they
provide.

A build server such as bloop centralizes the implementation of these basic
Scala features, provides the best developer experience to Scala users,
simplifies future Scala integrations and lets maintainers focus on one single
implementation to track performance, reliability and success metrics.

### Outlive Build Clients, Ease Integration

There are all kinds of build clients: short-lived and long-lived, JVM-based
and native, local and remote. Yet, regardless of the nature of the clients,
the Scala toolchain runs on the JVM and needs to favor single long-lived
sessions to minimize cpu/memory consumption and run at peak performance
—otherwise, end users pay a price; compilation performance in a long-lived
process can be up to 20x faster than a short-lived compiler).

A client-server architecture enables different clients to share optimized
compilers and build instances, minimizing developer latency and deduplicating
the work to warm up hot compilers in every client session.

### Optimize for the Most Common Scenarios

Edit, compile and test workflows are the bread and butter of software
development. When our build or editor are slow to respond, our productivity
drops.

Bloop lays stress on optimizing local development workflows to make the Scala
developer feedback cycle as short as possible. It achieves this by optimizing
compilations based on the user actions and minimizing the amount of work run
in every operation.


[scalacenter]: https://scala.epfl.ch
