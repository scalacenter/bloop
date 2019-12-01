---
id: version-1.3.4-integration
title: Integrate with the Build Server
sidebar_label: Integration Guide
original_id: integration
---

There are two ways to implement a client integration with Bloop's build server:

1. via the **Build Server Protocol**, recommended for text editors and build tools
1. via the **Command-Line Application**, recommended for lightweight clients such as scripts

<div class="diagram">
  <p>
    <img src="/bloop/img/bloop-architecture-diagram.svg" alt="Bloop architecture diagram">
  </p>
</div>

### Note on installation requirements

Every of the above mechanisms require you to have an environment with `bloop`
installed and a server running in the background. It's likely that won't always
hold for all your users. Can you still use `bloop`?

The answer is yes. The [Launcher Reference](launcher.md) explains how you can
use bloop's launcher to automatically install bloop and start a background
server, as well as establish an open bsp connection with it. Therefore, clients
that wish to integrate with bloop can provide an out-of-the-box experience to
their users.

## Build Server Protocol (BSP)

The [Build Server Protocol][bsp] is a work-in-progress protocol designed by the [Scala
Center](https://scala.epfl.ch) and [JetBrains](https://www.jetbrains.com/). Bloop is the first build
server to implement the protocol and provide build supports to clients such as
[Metals](https://scalameta.org/metals/) and [IntelliJ](https://www.jetbrains.com/idea/)

At the moment, Bloop 1.3.4+247-533137e6 partially implements version 2.0.0-M2 and Bloop v1.0.0 implements
version 1.0.0. If you want to implement a compatible build client, check out [the protocol
specification](https://github.com/scalacenter/bsp/blob/master/docs/bsp.md). As a client, the
protocol gives you fine-grained build and action information and it's more suitable for rich clients
such as build tools or editors.

### Creating a BSP client

BSP allows clients to have fine-grained information and control about the build tool actions. For
example, a client can obtain the projects in a build, inspect their metadata, trigger supported
tasks such as compilation, listen to compiler diagnostics and receive task notifications.

You can implement your BSP client:

1. via Java ([ch.epfl.scala:bsp4j:2.0.0-M2](https://github.com/scalacenter/bsp/tree/master/bsp4j/src/main)) based on [lsp4j](https://github.com/eclipse/lsp4j)
1. via Scala ([ch.epfl.scala:bsp4s:2.0.0-M2](https://github.com/scalacenter/bsp/tree/master/bsp4s/src/main)) based on [lsp4s](https://github.com/scalameta/lsp4s)

## Command-line application (CLI)

Lightweight build clients that don't need to control the output of the build server or obtain the
positions of compiler diagnostics can shell out to the bloop command-line application. This simple
integration is useful whenever you want to use bloop features in custom tools or scripts or when you
want to prototype a quick build tool integration.

To interface with the bloop CLI, head to the [CLI reference](cli-reference) to learn all the
supported commands.

### Generating configuration files

You may have a simple tool that wants to use Bloop but needs to generate Bloop configuration files.
For example, imagine that you are creating a small utility to run integration tests or you want to
assert that a certain code snippet doesn't compile in your tests.

To use bloop in these scenarios, you need to learn how to generate Bloop configuration files. Head
to the [JSON Configuration Reference](configuration-format) to learn more about what's required to
generate configuration files.

### Inspecting JSON files with scripts

Have you ever wanted to extract information from your build definition when writing a script for
your local development, your team or your CI? With bloop you can extract this information from its
configuration files via common CLI tools such as:

1. [gron](https://github.com/tomnomnom/gron) to simply grep JSON files.
2. [jq](https://stedolan.github.io/jq/) to process and extract in-depth JSON data.

[bsp]: https://github.com/scalacenter/bsp
