---
id: server-reference
title: Build Server Reference
sidebar_label: Build Server
---

The build server is at the core of bloop. It runs in the background and is
the responsible for scheduling and running client actions.

Bloop's build server is a long-running process designed to provide the fastest
compilation possible to users. As such, users are responsible for managing its
lifecycle and minimizing the amount of times it's restarted.

## Start the build server

At the end of the day, the build server is an artifact in Maven Central. However,
the recommended way of starting the server is via the `bloop server` invocation.

`bloop server` is an OS-independent way of starting the server that abstracts over
some of the messy details of running a JVM application with the right configuration.
For example, `bloop server`:

1. Finds the location of a bootstrapped jar automatically in the bloop installation
   directory
1. Runs the server with the JVM options configured in `$HOME/.bloop/bloop.json`, see
   [custom Java options](#custom-java-options).
1. Provides a way to evolve the way the server is run and managed in the future, which
   makes it especially compatibility-friendly.

The bloop installation directory is the directory where `bloop` is located. In Unix-based
systems, the bloop installation directory can be found by running `which bloop`.

<blockquote class="grab-attention">
If you are integrating your tool with bloop and want to install and start the server
<i>automatically</i> in the background, you can use Bloop's built-in <a href="launcher-reference">Launcher</a>.
</blockquote>

### `bloop server`

#### Usage

**Synopsis**: `bloop [FLAGS...] server [JVM_OPTS...] NAILGUN_PORT`

* `NAILGUN_PORT` must be a free TCP port. For now, only ports on localhost are
  supported.
* `JVM_OPTS` must be valid JVM arguments prefixed with `-J`, used to pass in
  temporary jvm options to the server. For a permanent solution, add the options
  to [`$HOME/.bloop/bloop.json`](#custom-java-options).

#### Flags

<dl>
  <dt><code>--server-location</code> (type: <code>path</code>)</dt>
  <dd><p>Use the server jar or script in the given path</p></dd>
</dl>

## Global settings for the server

Use the `$HOME/.bloop/bloop.json` file to configure the Bloop server. The
Bloop server must be restarted to pick up new configuration changes.

### Custom Java options

Update the `javaOptions` field to configure custom Java options that should
be used when starting a new Bloop server. For example, use this to increase
the memory to Bloop.

```jsonc
// $HOME/.bloop/bloop.json
{
  "javaOptions": ["-Xmx16G"]
}
```

### Custom Java home

Update the `javaHome` field declare what Java version the Bloop server should
run on. For example, use this to declare that Bloop should compile with JDK
11 or JDK 8.

```jsonc
// $HOME/.bloop/bloop.json
{
  "javaHome": "/Library/Java/JavaVirtualMachines/jdk1.8.0_111.jdk"
}
```

The `java` binary should exist in `$JAVA_HOME/bin/java`. The Bloop server
will not start correctly if the `javaHome` field points directly to the
`java` binary.

## Automatic management of the server

Depending on your operating system, there exist several solutions that allow you to
start, stop, restart and inspect the status of the build server at any time.

Both the bloop CLI and the launcher, the tool used by build clients to connect
to Bloop via BSP, will start the server if it's not running and connect to it.

You can exit the server by running `bloop exit` from the CLI. You can also kill
it with `kill`, the Activity Monitor in your machine or `htop`.

## Ignore exceptions in server logs

Bloop uses Nailgun, which sometimes prints exceptions in your server logs
such as:

```
Unable to load nailgun-version.properties.
NGServer [UNKNOWN] started on address localhost/127.0.0.1 port 8212.
[W] Internal error in session
java.io.EOFException
	at java.base/java.io.DataInputStream.readInt(DataInputStream.java:397)
	at com.martiansoftware.nailgun.NGCommunicator.readCommandContext(NGCommunicator.java:140)
	at com.martiansoftware.nailgun.NGSession.run(NGSession.java:197)
```

You can safely ignore these warnings and exceptions.
