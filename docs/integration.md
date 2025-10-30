---
id: integration
title: Integrate with the Build Server
sidebar_label: Integration Guide
---

There are two ways to implement a client integration with Bloop's build server:

1. via the **Build Server Protocol**, recommended for text editors and build
   tools
1. via the **Command-Line Application**, recommended for lightweight clients
   such as scripts

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

The [Build Server Protocol][bsp] is a work-in-progress protocol designed by the
[Scala Center](https://scala.epfl.ch) and
[JetBrains](https://www.jetbrains.com/). Bloop is the first build server to
implement the protocol and provide build supports to clients such as
[Metals](https://scalameta.org/metals/) and
[IntelliJ](https://www.jetbrains.com/idea/)

At the moment, Bloop @VERSION@ partially implements version 2.0.0-M2 and Bloop
v1.0.0 implements version 1.0.0. If you want to implement a compatible build
client, check out
[the protocol specification](https://github.com/scalacenter/bsp/blob/master/docs/bsp.md).
As a client, the protocol gives you fine-grained build and action information
and it's more suitable for rich clients such as build tools or editors.

### Creating a BSP client

BSP allows clients to have fine-grained information and control about the build
tool actions. For example, a client can obtain the projects in a build, inspect
their metadata, trigger supported tasks such as compilation, listen to compiler
diagnostics and receive task notifications.

You can implement your BSP client:

1. via Java
   ([ch.epfl.scala:bsp4j:2.0.0-M2](https://github.com/scalacenter/bsp/tree/master/bsp4j/src/main))
   based on [lsp4j](https://github.com/eclipse/lsp4j)
1. via Scala
   ([ch.epfl.scala:bsp4s:2.0.0-M2](https://github.com/scalacenter/bsp/tree/master/bsp4s/src/main))
   based on [lsp4s](https://github.com/scalameta/lsp4s)

## Command-line application (CLI)

Lightweight build clients that don't need to control the output of the build
server or obtain the positions of compiler diagnostics can shell out to the
bloop command-line application. This simple integration is useful whenever you
want to use bloop features in custom tools or scripts or when you want to
prototype a quick build tool integration.

To interface with the bloop CLI, head to the [CLI reference](cli/reference) to
learn all the supported commands.

### Generating configuration files

You may have a simple tool that wants to use Bloop but needs to generate Bloop
configuration files. For example, imagine that you are creating a small utility
to run integration tests or you want to assert that a certain code snippet
doesn't compile in your tests.

To use bloop in these scenarios, you need to learn how to generate Bloop
configuration files. Head to the
[JSON Configuration Reference](configuration-format) to learn more about what's
required to generate configuration files.

### Inspecting JSON files with scripts

Have you ever wanted to extract information from your build definition when
writing a script for your local development, your team or your CI? With bloop
you can extract this information from its configuration files via common CLI
tools such as:

1. [gron](https://github.com/tomnomnom/gron) to simply grep JSON files.
2. [jq](https://stedolan.github.io/jq/) to process and extract in-depth JSON
   data.

[bsp]: https://github.com/scalacenter/bsp

### When does Bloop compile projects?

Bloop will only compile projects if there is anything that changed since the
last compilation.

The changes that Bloop will check are:

- any changes to the the jar files in the project (though Bloop will not check
  the contents of the jar files)
- any changes to the source files
- any changes in the external APIs of the targets that the current target
  depends on
- any removals of previously compiled products (classes, semanticdb files, etc.)

If there was no change in any of the above, Bloop will not compile the project
and will return a no-op compilation for a given target. This includes cases
where the current target was compiled in a previous connection to Bloop or by a
separate client such as CLI.

For more details see `detectInitialChanges` method in the `BloopNameHashing`
class.

When compilation is noop, Bloop will send that information to the client in the
return message of the compile request. This is done by setting the
`isCompilationNoop` field to true in the `ScalaCompileReport` message available
under the data field of the `CompileResult` message.

The `ScalaCompileReport` message is defined as follows:

```
case class ScalaCompileReport(
      errors: Int,
      warnings: Int,
      isCompilationNoop: Boolean,
      compilationHashes: Map[String, Int]
)
```

Is `isCompilationNoop` set to true only when the compilation didn't cause any
target to be compiled. This means that a compilation could have take before with
a request from a different client.

The `compilationHashes` field is a map of project URIs to the hashes of the
analyses of the projects. These hashes are unique for each compilation and can
be used to determine if the compilation was a noop in terms of the current
client. It's especially useful to ensure the compilation wasn't done by a
different client and the current client were just notified about the same
compilation.

Bloop will also send additional notifications for each project that had a noop
compilation, but only if most recent change was reverted. For example, when a
user broke a file by adding an error and later removed it, Bloop will send a
notification for the project that had a noop compilation to signify that despite
the changes no compilation was needed. If no task notifications were sent after
compile request, the client can assume that the compilation was a noop. However,
it's much more convenient to use the `isCompilationNoop` field in the
`ScalaCompileReport` message.
