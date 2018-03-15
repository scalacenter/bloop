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
tool, whether it’s sbt, Maven, Gradle, Bazel, Pants, cbt or mill.

### Run as fast as possible

The main goal of bloop is to compile, test and run your code as fast as possible.

#### Hot compilers

The primary reason why Bloop brings you a tight developer workflow is because
it caches hot compilers across all developer tools (IDEs, build tools,
terminals).

A hot compiler is a long-running compiler that has been heavily optimized by
JVM's [JIT](https://en.wikipedia.org/wiki/Just-in-time_compilation) and
therefore reaches peak performance when compiling code. The difference
between a cold and hot compiler varies. This variation is usually an order of
magnitude large — a hot compiler can be between 5x and 20x faster than a cold
compiler.

Keeping hot compilers alive is crucial to be productive. However, it is a
difficult task; compilers must outlive short- and long-running build
processes, and there is no existing way to share them across different build
tools and IDEs.

Bloop is an agnostic build server that makes hot compiler accessible to all
your toolchain — IDEs, build tools and terminals can share hot compilers and
get peak performance at all times.

##### A better default for compiler lifetime

By supporting popular build tools out of the box and different scenarios,
`bloop` enforces by design best practices to be productive. It therefore
addresses the limitations of many build tools in the Scala ecosystem whose
infrastructure does not allow them to keep hot compilers alive.

This is especially true for sbt, the most popular build tool in our community.

1. `reload`: Every time you change a line of your `build.sbt`, you need to
   reload your shell to detect the changes in the sbt sources. In that process,
   sbt starts afresh and throws away all the compilers in their cache.

2. `sbt`: Every time you run `sbt` on your terminal, sbt starts a new
    compiler. This happens when:

    * You run sbt in different project directories, or have different
    builds running at the same time.

    * The sbt shell is killed. Some examples: you <kbd>CTRL</kbd> +
    <kbd>C</kbd> a misbehaving application (like an http server), you want to
    kill a command that you mistyped or sbt runs out of memory.

The design of Bloop has been thought through so that hot compilers are thrown
away only if you forcefully kill the Bloop server.

#### Run only core tasks

The other main way bloop achieves a tight developer workflow is by doing less
work. Bloop only focuses on tasks related to compiling, testing and running
your code. Everything else is left to external tools so that only the
blocking tasks are on your way when editing code.

As a result, you won't resolve library dependencies in the middle of an
incremental compilation run, nor run a task cache invalidation check, because
none of these are supported.

### Not a build tool

Bloop is not a new build tool.

Build tool is a concept used to refer to developer applications that offer
their users a way to write the logic of their build. They usually have a task
engine, a story for caching and incremental execution, a host DSL to
configure the build tool, a plugin ecosystem and other functionality.

This generic model allow build tools to express any build logic a user may
desire. This model usually comes with a trade off between speed and other
properties like correctness, usability and extensibility.

Bloop fundamentally lacks such a model because it doesn't aim to solve the
same problems a build tool does! The problems that bloop aims to address are:

* Compiling, testing and running code in the fastest way possible.
* Provide the backbone so that other tools can build upon it.

It can then be used by Scala developers to be more productive and by tooling
authors to write tools in a fast and reliable way. As a result, many build
tools, IDEs and client can reuse Bloop to have a fast developer workflow (and
avoid manually integrating with Scala and Java incremental compilers).

#### A note on the future of build tools

It's an exciting time for Scala tooling!

There is a lot of activity in the build tools field; many smart
people are working on how to make build tools in the Scala ecosystem faster,
easier to use and more correct.

Among these efforts we find organizations like Stripe or Wix working on
[better Scala support for Bazel](scala-bazel) or Twitter getting excellent
Scala support in [Pants](pants), and individuals like [Jan Christopher
Vogt](@cvogt) or [Li Haoyi](lihaoyi) working on [cbt] and [mill]
respectively.

All of these are exciting projects. In the future, we expect some of them to
integrate with Bloop and benefit from a faster cross build-tool workflow! But
we're also aware that they incidentally address the problem of compiling
faster in different ways.

With this in mind, we've created Bloop to primarly support all the existing
build tools that companies and Scala open source developers use *today*. We
believe that it's possible for many of these new tooling efforts to use Bloop
to get a faster developer workflow, but that's not our focus since the
community around them is small at the moment.

By supporting reliable JVM build tools like sbt, Maven and Gradle, we want to
make the life of any Scala developer in the world better.

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
[@cvogt]: https://github.com/cvogt
[@lihaoyi]: https://github.com/lihaoyi
[pants]: https://github.com/pantsbuild/pants
[scala-bazel]: https://github.com/bazelbuild/rules_scala
[cbt]: https://github.com/cvogt/cbt
[mill]: https://github.com/lihaoyi/mill