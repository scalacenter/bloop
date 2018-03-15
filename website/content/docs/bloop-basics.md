+++
toc = true
weight = 2
draft = false
title = "Bloop basics"
date = "2018-02-09T10:15:00+01:00"
description = "What is bloop and which problems does it solve?"
bref = "Learn the ideas behind bloop, which problems it solves and how it's designed"
+++

## Design goals

Bloop is a Scala command-line tool and build server developed at the Scala
Center to make the compile and test feedback loop as tight as possible. This
means a faster and more productive developer workflow.

### Tight feedback loop

Edit, compile and test workflows are the bread and butter of our daily jobs.
When our build is slow to respond, our productivity drops. Bloop is a
command-line tool and build server that brings you a tighter developer
workflow. Slow Scala compile times can often be attributed to the slugishness
of our build tool, and `bloop` aims to address them.

We have created bloop to make you productive without getting in your way. We
focus on how you can be faster at writing Scala code using your current build
tool, whether it’s sbt, Maven or Gradle.

### Run as fast as possible

The primary goal of bloop is to run core tasks as fast as possible.

#### Hot compilers

One of the primary ways Bloop achieves its goal is by caching hot compilers
across all developer tools (IDEs, build tools, terminals).

A hot compiler is a long-running compiler that has been heavily optimized by
JVM's [JIT](https://en.wikipedia.org/wiki/Just-in-time_compilation) and
therefore reaches peak performance when compiling code. The difference
between a cold and hot compiler varies. This variation is usually an order of
magnitude large — a hot compiler can be between ~5x and 20x faster than a
cold compiler.

Keeping hot compilers alive is crucial to be productive. However, it is a
difficult task; compilers must outlive short- and long-running build
processes, and there is no existing way to share them across different build
tools and IDEs.

Bloop is an agnostic build server that makes hot compiler accessible to all
your toolchain — IDEs, build tools and terminals can share hot compilers and
get peak performance at all times.

##### A better default for hot compilers

By supporting popular build tools out of the box and different scenarios,
`bloop` enforces by design best practices to be productive.

It therefore addresses the limitations of many build tools in the Scala
ecosystem whose infrastructure does not allow them to keep hot compilers
alive.

This is especially true for sbt, the standard Scala build tool, in which
every of the following actions start with a cold compiler afresh.

1. `reload`: Every time you change a line of your `build.sbt`, you need to
    reload your shell to detect the changes in the sbt sources. In that process, sbt starts afresh and throws away all the compilers in their cache.
2. `sbt`: Every time you run `sbt` on your terminal, you start afresh. This
    happens in two main scenarios:

    * You run sbt in different project directories, or have different
    builds running at the same time.

    * The sbt shell is killed. This can happen in several scenarios; for
    example, you <kbd>CTRL</kbd> + <kbd>C</kbd> a misbehaving application
    (like an http server), you type an incorrect sbt command and wants to
    finish it, or you
    
The lifetime of compilers in Bloop has been thought through so that there is
only one scenario in which hot compilers are killed: if you forcefully kill
the Bloop server.

#### Do less work

The other main way bloop achieves a tight developer workflow is by doing less
work. Bloop only focuses on compiling, testing and running your code.
Everything else is left to external tools so that only the blocking tasks are
on your way when editing code.

As a result, you won't resolve library dependencies in the middle of an
incremental compilation run, nor run cache invalidation to check if a task
result changed in your build.

### Not a build tool

Bloop is not a new build tool.

In the modern use of the word, build tools are developer applications that
offer their users a way to write the logic of their build. The uses of a build tool span They usually have
a task engine, a story for caching and incremental execution, a host DSL to
configure the build tool, a plugin ecosystem and much more.

It can be used by Scala developers to be more productive and by
tooling authors to write tools in a fast and reliable way.

There is a lot of progress and research going into the build tools field.
Whether you use established build tools like Bazel, Pants, sbt, Maven and
Gradle or brand-new build tools like CBT and mill, you should benefit from
the fastest of the workflows no matter what the internal architecture of your
build tool is.

#### Runtime independent

Test or run your Scala application on the JVM, Scala.js and Scala Native.
Bloop interfaces with the API of every runtime that the Scala ecosystem
supports so that external tools don't have to.

#### Command line friendly

The primary way to interact with bloop is directly from the command line
instead of through a shell or repl. This workflow is more familiar to how
most of developer tools work (for example, Maven, Gradle or sbt) and allows
you to reuse the same terminal for other tasks.

#### Simplicity

You can think of bloop as a simple, end-user application that runs Bloop's
core commands. The does not require you to learn a complex DSL or
configuration format to interact with it. `--help` takes you a long way.

#### Concurrent

A single bloop server handles concurrent requests from multiple clients. This
enables several clients to reuse the same bloop instance without blocking
each other.

[scala/scala]: https://github.com/scala/scala
[sbt/zinc]: https://github.com/sbt/zinc
