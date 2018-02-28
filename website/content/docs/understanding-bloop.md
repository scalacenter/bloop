+++
toc = true
weight = 2
draft = false
title = "Understanding Bloop"
date = "2018-02-09T10:15:00+01:00"
description = "Learn the ideas behind bloop, which problems solve and how it's designed"
bref = "Learn the ideas behind bloop, which problems solve and how it's designed."
+++

## An overview of Bloop

Bloop is a Scala command-line tool and build server developed at the Scala
Center to make the compile and test feedback loop as tight as possible. This
means a faster and more productive developer workflow.

### A common pain point

Edit, compile and test workflows are the bread and butter of our daily jobs.
When our build is slow to respond, our productivity drops. Can Scala get as
tight edit/compile/test workflows as in Clojure or OCaml? Bloop is a
command-line tool and build server that brings you a tighter developer
workflow. Slow Scala compile times can often be attributed to the slugishness
of our build tool, and `bloop` finds a solution to them.

We have created bloop to make you productive without getting in your way. We
focus on how you can be faster at writing Scala code using your current build
tool, whether it’s sbt, Maven or Gradle—without reinventing the wheel with a
new build tool.

### The main goal

The primary goal of bloop is to keep your hot compilers around.

A hot compiler is a long-running compiler that has been heavily optimized by
JVM's [JIT](https://en.wikipedia.org/wiki/Just-in-time_compilation) and
therefore reaches peak performance when compiling code. The difference
between a cold and hot compiler varies. This variation is usually an order of
magnitude large — a hot compiler can be between 10x and 20x faster than a
cold compiler.

Keeping hot compilers alive is crucial to be productive. However, it is a
difficult task; compilers must outlive short- and long-running build
processes, and there is no existing way to share them across different build
tools and IDEs.

Bloop is an agnostic build server that makes hot compiler accessible to all
your toolchain — IDEs, build tools and terminals can share hot compilers and
get peak performance at all times.

By supporting popular build tools out of the box and different scenarios,
`bloop` enforces by design best practices to be productive.

### What bloop is not

Bloop is **not** a new build tool, but rather a foundation block for Scala
tooling. It can be used by Scala developers to be more productive and by
tooling authors to write tools in a fast and reliable way.

There is a lot of progress and research going into the build tools field.
Whether you use established build tools like Bazel, Pants, sbt, Maven and
Gradle or brand-new build tools like CBT and mill, you should benefit from
the fastest of the workflows without being tied to your build tool.

### Components

Bloop has two main components, a client and a server.

#### `bloop`, the client

The `bloop` client is the tool that you use to run any bloop command. The
client is a python script that implements Nailgun's protocol and communicates
with the server. It is a fast CLI tool that gives you immediate feedback from
the server.

#### `blp-server`, the server

The server is called `blp-server`, and it's a long-running application that
runs on the background and keeps the state of all your projects and compiler
instances.

When you run `bloop compile` with the client, the server receives the request
and spawns a thread to compile your project, logging you back everything that
the compiler outputs. This server accepts requests in parallel except for
those tasks that are already running.

### Other properties

#### Runtime independent

Test or run your Scala application on the JVM, Scala.js and Scala Native.
Bloop interfaces with the API of every runtime that the Scala ecosystem
supports so that external tools don't have to.

#### Familiarity

Bloop is not a long running application like the sbt shell. You run it, get
the result, and you have your shell back. This is closer to the workflow that
many developers love and are used to in other tools like Maven, Gradle and
npm.

#### Simplicity

You can think of bloop as a dummy end-user application that supports build
core commands. Bloop does not require you to learn a complex DSL to interact
with it, or a complicated configuration format. `--help` takes you a long
way.

#### Rolling release

Bloop releases feature the latest of the advancements in compiler APIs. If
you use it, you will be the first one to benefit from performance speedups,
bugfixes and other changes occurring upstream in both [scala/scala] and
[sbt/zinc].

#### Concurrent

Bloop supports several users by design so that concurrent executions can take
place without stepping on each other's shoes.

[scala/scala]: https://github.com/scala/scala
[sbt/zinc]: https://github.com/sbt/zinc