---
id: version-1.2.0-what-is-bloop
title: What is Bloop
sidebar_label: What is Bloop
original_id: what-is-bloop
---

Bloop is a Scala build server developed by [the Scala Center][scalacenter]. It has three main goals:

1. Compiles, tests and runs Scala code as fast as possible
2. Is an independent and long-lived process reusable by multiple clients
3. Integrates easily with build tools, command-line applications, editors and custom tooling

## Features

1. Compiles a vast array of Java and Scala (2.10.x, 2.11.x, 2.12.x and 3.x) versions
2. Runs and tests on the Java, [Scala.js](https://www.scala-js.org/) and [Scala Native](https://github.com/scala-native/scala-native) runtimes
3. Integrates with common build tools in the Scala ecosystem (sbt, Gradle, etc)

## How does it work

The core component of bloop is the build server. The build server is responsible of implementing the
logic to compile, test and run Scala and Java projects. It is designed to run on the background for
long periods of time (as long as your machine is running) and assist all client's requests.

Clients can connect to the build server in two ways:

1. via the [Nailgun server protocol](https://github.com/facebook/nailgun), used by the built-in command-line application
2. via the [Build Server Protocol (BSP)](https://github.com/scalacenter/bsp), used by clients such as [Metals](https://github.com/scalameta/metals) or [IntelliJ](https://www.jetbrains.com/idea/)

> Do you want to integrate with bloop? The [Integration Guide](integration.md) explains all
> you need to know to connect to Bloop via these two different protocols.

## The Principles

The lack of clear design principles in previous Scala tools has hindered progress in the tooling
community, complicated maintenance and worsened the Scala user experience with, for example, slower
compiles. Bloop addresses such problems with three design principles that shape its design and
improve on the status quo.

### Implement Once, Optimize Once, Use Everywhere

Every developer tool supporting Scala needs to compile, test and run Scala code. These are basic
features that both old and new tools alike require, from build tools to IDEs/editors, in-house
tooling and custom scripts.

Implementing custom client integrations is a tedious task for the close-knit community of Scala
tooling contributors. In practice, contributors repeat the same learning process, duplicate the
integration logic and its optimizations, have trouble benchmarking compilation performance
consistently across all clients and lack ways to assess the quality of the Scala user experience
they provide.

A build server such as bloop centralizes the implementation of these basic Scala features, provides
the best developer experience to Scala users, simplifies future Scala integrations and lets
maintainers focus on one single implementation to track performance, reliability and success
metrics.

### Outlive Build Clients, Ease Integration

There are all kinds of build clients: short-lived and long-lived, JVM-based and native, local and
remote. Yet, regardless of the nature of the clients, the Scala toolchain runs on the JVM and needs
to favor single long-lived sessions to  minimize cpu/memory consumption and run at peak performance
(for example, compilation performance in a long-lived process can be up to 20x faster than a
short-lived compiler).

A client-server architecture enables different clients to share optimized compilers and build
instances, minimizing developer latency and deduplicating the work to warm up hot compilers in every
client session.

### Optimize for the Most Common Scenarios

Edit, compile and test workflows are the bread and butter of software development. When our build or
editor are slow to respond, our productivity drops.

Bloop lays stress on optimizing local development workflows to make the Scala developer feedback
cycle as short as possible. It achieves this by optimizing compilations based on the user actions
and minimizing the amount of work run in every operation.

[scalacenter]: https://scala.epfl.ch

