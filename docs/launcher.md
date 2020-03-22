---
id: launcher-reference
title: Launcher Reference
sidebar_label: Built-in Launcher
---

The launcher is a lightweight artifact (~122K) that installs and configures
bloop. The goal of the launcher is to relieve clients of the burden of
installing and managing the lifetime of background build servers.

The launcher implements the [Build Server Discovery protocol][server-discovery]
which allows clients to establish a bsp connection and communicate with the
server via stdin and stdout.

## `launcher`

#### Usage

**Synopsis**: `launcher [JVM_OPTS...] [FLAGS...] BLOOP_VERSION`

* `BLOOP_VERSION` must be a bloop version >= 1.2.0. This version is only
  used in case an installation is tried.
  > There is no guarantee that the server provided to the client will be of this version.
* `JVM_OPTS` must be valid JVM arguments prefixed with `-J`.

#### Flags

<dl>
  <dt><code>--skip-bsp-connection</code> (type: <code>bool</code>)</dt>
  <dd><p>Do not attempt a bsp connection, but exit as soon as bloop has been installed and a server is running in the background.</p></dd>
</dl>

## Description

The high-level logic steps of the launcher can be summarized as follows:

1. If bloop is not detected in the `$PATH` or `$HOME/.bloop/`, install it.
   * If the universal installation (that requires `curl` and `python`) fails,
     then we attempt to resolve bloop and launch an embedded server with
     a concrete bsp command. This operation is only attempted if and only if
     `--skip-bsp-connection` is not used.
1. Run a server in the background if it's absent.
1. When a server is running:
   * If `--skip-bsp-connection`: return
   * Else establish a bsp connection with the server and forward any message
     from the launcher stdin to the server and from the server to the launcher
     stdout.

### Reuse server in the background

The launcher will always try to reuse a server already running or spawned by
previous launcher executions to ensure that build servers are as hot and
efficient as possible.

### Use the launcher when off-line

The launcher is designed to be off-line friendly, e.g. if after running the
launcher there is no internet connection, the launcher will not need to hit
internet again and will instead populate data from caches. The launcher will
fail fatally in off-line scenarios if and only if the server is not installed
from the start.

## Usage

### Run the launcher manually

Clients can depend on the launcher and run it when they see fit. The flag
`--skip-bsp-connection` is particularly useful for clients wishing to install
and spawn a bloop version, but that do not wish to use BSP and instead prefer
to detect manually the installed `bloop` version by the launcher and [run the
CLI](cli/reference).

#### Install the launcher

```scala mdoc:launcher-releases
I am going to be replaced by the docs infrastructure.
```

The launcher is independent from bloop but it's released with the same cadence than the bloop build server. Therefore, you can expect a build server version to have an accompanying launcher version.

### Open a BSP connection

To open a bsp a bsp connection with Bloop's build server you need
to follow the [Build Server Discovery protocol][server-discovery]. BSP allow
clients to connect to a BSP server in a build-tool-independent way through BSP
connection files, a JSON file with build server metadata and an `argv` field
that, if shelled out, will establish a bsp connection with a server.

The bloop BSP connection file is created automatically by any of the supported
bloop installation methods (in the global `.bsp` directory) or by any of its
build plugins (in the `.bsp` workspace directory). If you do not depend on
a build plugin or require the installation of bloop, you are required to create
a bloop BSP connection file to allow you and other servers follow the Build
Server Protocol.

[server-discovery]: https://github.com/scalacenter/bsp/blob/v2.0.0-M2/docs/bsp.md#bsp-connection-protocol
