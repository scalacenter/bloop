---
id: bloop-rifle
title: Launcher Reference
sidebar_label: Built-in Launcher
---

Bloop rifle is a lightweight artifact that installs and configures bloop. The
goal of Bloop rifle is to relieve clients of the burden of installing and
managing the lifetime of background build servers.

Bloop rifle implements the [Build Server Discovery protocol][server-discovery]
which allows clients to establish a BSP connection and communicate with the
server via stdin and stdout.

For an example of using Bloop rifle, see the
[Bloop CLI module](https://github.com/scalacenter/bloop/tree/main/cli) and
[Metals](https://github.com/scalameta/metals/blob/main/metals/src/main/scala/scala/meta/internal/metals/BloopServers.scala)

## Description

Using Bloop rifle directly is more complex than using the CLI.

1. Clients that want to start Bloop need to use `BloopRifleConfig`, which has some
   default values, but it needs a way to download Bloop binaries provided via
   classpath options.

```scala
final case class BloopRifleConfig(
    address: BloopRifleConfig.Address,
    javaPath: String,
    javaOpts: Seq[String],
    classPath: String => Either[Throwable, Seq[File]],
    workingDir: File,
    bspSocketOrPort: Option[() => BspConnectionAddress],
    bspStdin: Option[InputStream],
    bspStdout: Option[OutputStream],
    bspStderr: Option[OutputStream],
    period: FiniteDuration,
    timeout: FiniteDuration,
    startCheckPeriod: FiniteDuration,
    startCheckTimeout: FiniteDuration,
    initTimeout: FiniteDuration,
    minimumBloopJvm: Int,
    retainedBloopVersion: BloopRifleConfig.BloopVersionConstraint
)
```

At a minimum users need 3 arguments:

```scala
  def default(
      address: Address,
      bloopClassPath: String => Either[Throwable, Seq[File]],
      workingDir: File
  ): BloopRifleConfig =
```

- `address| - the address of the server, either TCP or via a domain socket
  path
- `bloopClassPath` - a function that takes a string and returns a sequence of
  files, needed to download bloop binaries
- `workingDir` - the working directory of the server

2. With config available users can use `BloopRifle.check` to check if Bloop is
   running or `BloopRifle.start` to start the server.
3. Lastly, `BloopRifle.bsp` can be used to establish a connection to the server.
   It will return a `BspConnection` class, which will have a `openSocket` method
   available.

#### Install Bloop Rifle

```scala mdoc:rifle-releases
I am going to be replaced by the docs infrastructure.
```

Bloop Rifle is independent from Bloop but it's released with the same cadence
than the Bloop build server. Therefore, you can expect a build server version to
have an accompanying rifle version.

### Open a BSP connection

To open a BSP connection with Bloop's build server you need to follow the
[Build Server Discovery protocol][server-discovery]. BSP allow clients to
connect to a BSP server in a build-tool-independent way through BSP connection
files, a JSON file with build server metadata and an `argv` field that, if
shelled out, will establish a bsp connection with a server.

The bloop BSP connection file is created automatically by any of the supported
bloop installation methods (in the global `.bsp` directory) or by any of its
build plugins (in the `.bsp` workspace directory). If you do not depend on a
build plugin or require the installation of bloop, you are required to create a
bloop BSP connection file to allow you and other servers follow the Build Server
Protocol.

[server-discovery]:
  https://github.com/build-server-protocol/build-server-protocol/blob/master/docs/overview/server-discovery.md
