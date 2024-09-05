---
id: server-reference
title: Build Server Reference
sidebar_label: Build Server
---

The build server is at the core of bloop. It runs in the background and is the
responsible for scheduling and running client actions.

Bloop's build server is a long-running process designed to provide the fastest
compilation possible to users. As such, users are responsible for managing its
lifecycle and minimizing the amount of times it's restarted.

## Start the build server

At the end of the day, the build server is an artifact in Maven Central.
However, the recommended way of starting the server is via the `bloop start`
invocation.

`bloop start` is an OS-independent way of starting the server that abstracts
over some of the messy details of running a JVM application with the right
configuration. For example, `bloop start`:

1. Finds a JDK to use on your system or downloads a JDK if it's not available.
1. Runs the server with the JVM options configured via `BLOOP_JAVA_OPTS`
   environment variable or `bloop.java-opts` property, see
   [custom Java options](#custom-java-options).
1. Makes sure that the server is running it connects to that specific server.

<blockquote class="grab-attention">
If you are integrating your tool with bloop and want to install and start the server
<i>automatically</i> in the background, you can use Bloop's built-in <a href="bloop-rifle">bloop rifle</a>.
</blockquote>

### `bloop start`

#### Usage

```bash
Usage: bloop start [options]
Starts a Bloop instance, if none is running

Help options:
  --usage            Print usage and exit
  -h, -help, --help  Print help message and exit

Other options:
  -v, --verbose                     Increase verbosity (can be specified multiple times)
  -q, --quiet                       Decrease verbosity
  --progress                        Use progress bars
  --home, --home-directory string?
  --java-home path                  Set the Java home directory
  -j, --jvm jvm-name                Use a specific JVM, such as `14`, `adopt:11`, or `graalvm:21`, or `system`
  -f, --force
```

### Custom Java options

By default, Bloop server when run via the native launcher is configured to use:

```
  "-Xss4m",
  "-XX:MaxInlineLevel=20", // Specific option for faster C2, ignored by GraalVM
  "-XX:+UseZGC", // ZGC returns unused memory back to the OS, so Bloop does not occupy so much memory if unused
  "-XX:ZUncommitDelay=30",
  "-XX:ZCollectionInterval=5",
  "-XX:+IgnoreUnrecognizedVMOptions", // Do not fail if an non-standard (-X, -XX) VM option is not recognized.
  "-Dbloop.ignore-sig-int=true"
```

Update the `BLOOP_JAVA_OPTS` env variable or `bloop.java-opts` property to
configure custom Java options that should be used when starting a new Bloop
server. For example, use this to increase the memory to Bloop.

```bash
export BLOOP_JAVA_OPTS="-Xmx16G -XX:+UseZGC -Xss4m"
```

### Custom Java home

By default Bloop cli will try to find JDK 17 and up on your system, if it's not
possible it will download a JDK 17 from the internet. If you want to use a
different JDK, please make sure that JAVA_HOME is set correctly or use
`--java-home` option.

```bash
bloop start --java-home "/Library/Java/JavaVirtualMachines/jdk1.8.0_111.jdk"
```

The `java` binary should exist in `$JAVA_HOME/bin/java`. The Bloop server will
not start correctly if the `javaHome` field points directly to the `java`
binary.

## Automatic management of the server

Depending on your operating system, there exist several solutions that allow you
to start, stop, restart and inspect the status of the build server at any time.

Both the bloop CLI and the bloop rifle, the tool used by build clients to
connect to Bloop via BSP, will start the server if it's not running and connect
to it.

You can exit the server by running `bloop exit` from the CLI. You can also kill
it with `kill`, the Activity Monitor in your machine or `htop`.

## Ignore exceptions in server logs

Bloop uses Nailgun, which sometimes prints exceptions in your server logs such
as:

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
